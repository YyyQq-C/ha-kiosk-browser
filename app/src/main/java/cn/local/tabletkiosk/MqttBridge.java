package cn.local.tabletkiosk;

import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/**
 * MQTT 连接：上线后发布 HA 自动发现和状态，订阅命令主题；
 * 所有网络操作都在单独的工作线程执行，密码不写入日志。
 */
final class MqttBridge {
    private static final long FIRST_CONNECT_RETRY_SECONDS = 30;
    private static final String ONLINE = "online";
    private static final String OFFLINE = "offline";

    private final KioskHub hub;
    private final MqttDiscovery.Device device;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private KioskConfig config;
    private MqttClient client;
    private volatile MqttTopics topics;
    private int generation;
    private ScheduledFuture<?> periodic;
    private ScheduledFuture<?> retry;
    private Boolean publishedOwner;

    /** 绑定状态中心和设备信息。 */
    MqttBridge(KioskHub hub, MqttDiscovery.Device device) {
        this.hub = hub;
        this.device = device;
    }

    /** 应用新配置；连接参数变化时重连，否则只调整上报间隔。 */
    void apply(KioskConfig next) {
        worker.execute(() -> {
            boolean reconnect = config == null || !next.sameMqttConnection(config);
            config = next;
            if (reconnect) {
                disconnect();
                if (next.mqttEnabled) connect();
            }
            schedulePeriodic();
        });
    }

    /** 状态变化时立即上报。 */
    void publishState() {
        worker.execute(this::publishStateNow);
    }

    /** 断开并停止工作线程，服务销毁时调用。 */
    void shutdown() {
        worker.execute(this::disconnect);
        worker.shutdown();
    }

    /** 建立连接；首次连接失败时定时重试，连上之后由 Paho 自动重连。 */
    private void connect() {
        generation++;
        int gen = generation;
        MqttTopics t = new MqttTopics(config.mqttBaseTopic, config.mqttDiscoveryPrefix, device.id);
        topics = t;
        String host = config.mqttHost.contains(":") ? "[" + config.mqttHost + "]" : config.mqttHost;
        String uri = "tcp://" + host + ":" + config.mqttPort;
        try {
            client = new MqttClient(uri, device.id, new MemoryPersistence());
            client.setCallback(new Callback(gen));
            client.connect(options(t));
            Log.i(KioskApp.LOG, "MQTT connected to " + uri);
        } catch (MqttException e) {
            Log.w(KioskApp.LOG, "MQTT connect failed to " + uri + ": reason=" + e.getReasonCode()
                    + " " + e.getMessage());
            closeClient();
            retry = worker.schedule(() -> {
                if (gen == generation && config.mqttEnabled && client == null) connect();
            }, FIRST_CONNECT_RETRY_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** 连接参数：自动重连、遗嘱消息和可选账号。 */
    private MqttConnectOptions options(MqttTopics t) {
        MqttConnectOptions o = new MqttConnectOptions();
        o.setCleanSession(true);
        o.setAutomaticReconnect(true);
        o.setMaxReconnectDelay(60_000);
        o.setConnectionTimeout(10);
        o.setKeepAliveInterval(30);
        if (!config.mqttUsername.isEmpty()) o.setUserName(config.mqttUsername);
        if (!config.mqttPassword.isEmpty()) o.setPassword(config.mqttPassword.toCharArray());
        // 意外断线时 broker 代发 offline，HA 中实体显示不可用
        o.setWill(t.availability(), OFFLINE.getBytes(StandardCharsets.UTF_8), 1, true);
        return o;
    }

    /** 主动断开：先发 offline，再关闭连接。 */
    private void disconnect() {
        generation++;
        if (retry != null) retry.cancel(false);
        retry = null;
        if (client != null && client.isConnected()) {
            publish(topics.availability(), OFFLINE, true);
            try {
                client.disconnect(2000);
            } catch (MqttException e) {
                Log.w(KioskApp.LOG, "MQTT disconnect failed: reason=" + e.getReasonCode());
            }
        }
        closeClient();
        publishedOwner = null;
        setConnected(false);
    }

    /** 释放客户端资源。 */
    private void closeClient() {
        if (client == null) return;
        try {
            client.close(true);
        } catch (MqttException e) {
            Log.w(KioskApp.LOG, "MQTT close failed: reason=" + e.getReasonCode());
        }
        client = null;
    }

    /** 每次连上（含自动重连）后：订阅命令、发布在线、注册实体并上报状态。 */
    private void onConnected(int gen) {
        if (gen != generation || client == null || !client.isConnected()) return;
        MqttTopics t = topics;
        try {
            client.subscribe(t.commandFilter(), 1);
            client.subscribe(t.haStatus(), 1);
        } catch (MqttException e) {
            Log.e(KioskApp.LOG, "MQTT subscribe failed: reason=" + e.getReasonCode(), e);
        }
        publish(t.availability(), ONLINE, true);
        publishDiscovery();
        publishStateNow();
        setConnected(true);
    }

    /** 发布全部实体的自动发现配置。 */
    private void publishDiscovery() {
        if (client == null || !client.isConnected()) return;
        boolean owner = hub.status().deviceOwner;
        for (MqttDiscovery.Message m : MqttDiscovery.build(topics, device, owner)) publish(m.topic, m.payload, true);
        publishedOwner = owner;
        Log.i(KioskApp.LOG, "MQTT discovery published, deviceOwner=" + owner);
    }

    /** 发布状态 JSON；设备所有者状态变化时先重新注册实体。 */
    private void publishStateNow() {
        if (client == null || !client.isConnected()) return;
        KioskStatus s = hub.status();
        if (publishedOwner != null && publishedOwner != s.deviceOwner) publishDiscovery();
        publish(topics.state(), StatusJson.of(s, config, false), true);
    }

    /** 按设置的间隔重复上报，0 表示只在变化时上报。 */
    private void schedulePeriodic() {
        if (periodic != null) periodic.cancel(false);
        periodic = null;
        int seconds = config.mqttStateIntervalSeconds;
        if (config.mqttEnabled && seconds > 0) {
            periodic = worker.scheduleWithFixedDelay(this::publishStateNow, seconds, seconds, TimeUnit.SECONDS);
        }
    }

    /** 以 QoS 1 发布文本消息。 */
    private void publish(String topic, String payload, boolean retained) {
        try {
            MqttMessage m = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            m.setQos(1);
            m.setRetained(retained);
            client.publish(topic, m);
        } catch (MqttException e) {
            Log.w(KioskApp.LOG, "MQTT publish failed on " + topic + ": reason=" + e.getReasonCode());
        }
    }

    /** 把连接状态写入状态中心。 */
    private void setConnected(boolean connected) {
        hub.updateStatus(b -> {
            b.mqttConnected = connected;
            return b;
        });
    }

    /** Paho 回调：转到工作线程处理，避免在回调线程里阻塞客户端。 */
    private final class Callback implements MqttCallbackExtended {
        private final int gen;

        /** 记录所属连接代次，旧连接的回调会被忽略。 */
        Callback(int gen) {
            this.gen = gen;
        }

        /** 首次连接和自动重连成功。 */
        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            if (reconnect) Log.i(KioskApp.LOG, "MQTT reconnected to " + serverURI);
            worker.execute(() -> onConnected(gen));
        }

        /** 连接断开，Paho 会自动重连。 */
        @Override
        public void connectionLost(Throwable cause) {
            Log.w(KioskApp.LOG, "MQTT connection lost: " + (cause == null ? "unknown" : cause.getMessage()));
            setConnected(false);
        }

        /** 处理命令和 HA 上线通知。 */
        @Override
        public void messageArrived(String topic, MqttMessage message) {
            MqttTopics t = topics;
            if (t == null) return;
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            MqttCommands.Command cmd = MqttCommands.parse(t, topic, payload);
            switch (cmd.type) {
                case RELOAD:
                    Log.i(KioskApp.LOG, "MQTT command: reload");
                    hub.requestReload();
                    break;
                case WAKE:
                    Log.i(KioskApp.LOG, "MQTT command: wake");
                    hub.requestWake();
                    break;
                case BRIGHTNESS:
                    Log.i(KioskApp.LOG, "MQTT command: brightness=" + cmd.value);
                    hub.requestWatchBrightness(cmd.value);
                    break;
                case REBOOT:
                    Log.i(KioskApp.LOG, "MQTT command: reboot");
                    hub.requestReboot();
                    break;
                case HA_ONLINE:
                    Log.i(KioskApp.LOG, "Home Assistant came online, republishing discovery");
                    worker.execute(() -> {
                        publishDiscovery();
                        publishStateNow();
                    });
                    break;
                default:
                    Log.w(KioskApp.LOG, "Ignored MQTT message on " + topic);
            }
        }

        /** 发布完成，无需处理。 */
        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // QoS 1 已由客户端确认
        }
    }
}
