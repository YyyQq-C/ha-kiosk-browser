package cn.local.tabletkiosk;

import android.content.Context;
import android.os.Build;
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
    private final Context context;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private KioskConfig config;
    private MqttClient client;
    private volatile MqttTopics topics;
    private volatile int generation;
    private ScheduledFuture<?> periodic;
    private ScheduledFuture<?> retry;
    private Boolean publishedOwner;

    /** 绑定状态中心；设备名称在发布时按当前语言读取。 */
    MqttBridge(KioskHub hub, Context context) {
        this.hub = hub;
        this.context = context.getApplicationContext();
    }

    /** 应用新配置；连接参数变化时重连，否则只调整上报间隔。 */
    void apply(KioskConfig next) {
        worker.execute(() -> {
            boolean reconnect = config == null || !next.sameMqttConnection(config);
            config = next;
            if (reconnect) {
                disconnect();
                // 用户刚点了保存并同步时，由 syncNow 连接并回报结果，这里不连第二次
                if (next.mqttEnabled && !hub.mqttSyncArmed()) connect();
            }
            schedulePeriodic();
        });
    }

    /** 状态变化时立即上报。 */
    void publishState() {
        worker.execute(this::publishStateNow);
    }

    /** 按已保存配置立刻同步：已连接则上报，未连接则马上重连，不等定时重试。 */
    void syncNow() {
        worker.execute(() -> {
            hub.clearMqttSyncArmed();
            if (config == null || !config.mqttEnabled) {
                hub.reportMqttLink(KioskHub.MqttLink.OFF, "", "");
                return;
            }
            String endpoint = endpointLabel(config);
            if (client != null && client.isConnected()) {
                Log.i(KioskApp.LOG, "MQTT sync publishing current state");
                publishStateNow();
                hub.reportMqttLink(KioskHub.MqttLink.OK, endpoint, "");
                return;
            }
            Log.i(KioskApp.LOG, "MQTT sync connecting now");
            if (retry != null) retry.cancel(false);
            retry = null;
            connect();
        });
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
        MqttTopics t = new MqttTopics(config.mqttBaseTopic, config.mqttDiscoveryPrefix, hub.deviceId());
        topics = t;
        String host = config.mqttHost.contains(":") ? "[" + config.mqttHost + "]" : config.mqttHost;
        String uri = "tcp://" + host + ":" + config.mqttPort;
        try {
            client = new MqttClient(uri, hub.deviceId(), new MemoryPersistence());
            client.setCallback(new Callback(gen));
            client.connect(options(t));
            Log.i(KioskApp.LOG, "MQTT connected to " + uri);
        } catch (MqttException e) {
            Log.w(KioskApp.LOG, "MQTT connect failed to " + uri + ": reason=" + e.getReasonCode()
                    + " " + e.getMessage());
            closeClient();
            hub.reportMqttLink(KioskHub.MqttLink.FAIL, endpointLabel(config), failureDetail(e));
            retry = worker.schedule(() -> {
                if (gen == generation && config.mqttEnabled && client == null) connect();
            }, FIRST_CONNECT_RETRY_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** 给界面看的主机和端口，不带账号和密码。 */
    private static String endpointLabel(KioskConfig current) {
        String host = current.mqttHost.contains(":") ? "[" + current.mqttHost + "]" : current.mqttHost;
        return host + ":" + current.mqttPort;
    }

    /** 失败说明：保留原因码和短消息，去掉密码。 */
    private String failureDetail(Throwable cause) {
        if (cause == null) return "";
        int code = cause instanceof MqttException ? ((MqttException) cause).getReasonCode() : -1;
        String message = cause.getMessage() == null ? "" : cause.getMessage().replace('\n', ' ').trim();
        String password = config == null ? "" : config.mqttPassword;
        if (!password.isEmpty()) message = message.replace(password, "");
        if (message.length() > 160) message = message.substring(0, 160);
        if (code >= 0 && message.isEmpty()) return Integer.toString(code);
        if (code >= 0) return message + " (" + code + ")";
        return message;
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
        hub.reportMqttLink(KioskHub.MqttLink.OK, endpointLabel(config), "");
    }

    /** 发布全部实体的自动发现配置。 */
    private void publishDiscovery() {
        if (client == null || !client.isConnected()) return;
        boolean owner = hub.status().deviceOwner;
        for (MqttDiscovery.Message m : MqttDiscovery.build(topics, device(), labels(), owner)) {
            publish(m.topic, m.payload, true);
        }
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

    /** 用当前语言重新发布发现信息。 */
    void republishDiscovery() {
        worker.execute(this::publishDiscovery);
    }

    /** 按已保存的界面语言取文案。 */
    private Context localized() {
        return AppLocales.wrap(context);
    }

    /** 按当前界面语言生成设备信息。 */
    private MqttDiscovery.Device device() {
        return new MqttDiscovery.Device(hub.deviceId(), localized().getString(R.string.app_name),
                Build.MANUFACTURER, Build.MODEL, BuildConfig.VERSION_NAME);
    }

    /** 按当前界面语言生成实体名称。 */
    private MqttDiscovery.Labels labels() {
        Context text = localized();
        return new MqttDiscovery.Labels(
                text.getString(R.string.entity_screen_on),
                text.getString(R.string.entity_presence),
                text.getString(R.string.entity_battery),
                text.getString(R.string.entity_charging),
                text.getString(R.string.entity_charge_state),
                text.getString(R.string.entity_brightness),
                text.getString(R.string.entity_watch_brightness),
                text.getString(R.string.entity_url),
                text.getString(R.string.entity_reload),
                text.getString(R.string.entity_wake),
                text.getString(R.string.entity_reboot),
                text.getString(R.string.entity_standby),
                text.getString(R.string.entity_motion),
                text.getString(R.string.entity_standby_brightness),
                text.getString(R.string.entity_dim_delay),
                text.getString(R.string.entity_lock),
                text.getString(R.string.entity_screen_off),
                text.getString(R.string.entity_home),
                text.getString(R.string.entity_open_url),
                text.getString(R.string.entity_screenshot),
                text.getString(R.string.entity_notify),
                text.getString(R.string.entity_speak),
                text.getString(R.string.entity_schedule),
                text.getString(R.string.entity_day_brightness),
                text.getString(R.string.entity_night_brightness),
                text.getString(R.string.entity_ip),
                text.getString(R.string.entity_app_version),
                text.getString(R.string.entity_model),
                text.getString(R.string.entity_android),
                text.getString(R.string.entity_volume),
                text.getString(R.string.entity_play),
                text.getString(R.string.entity_daily),
                text.getString(R.string.entity_daily_hour),
                text.getString(R.string.entity_daily_minute),
                text.getString(R.string.entity_sleep),
                text.getString(R.string.entity_sleep_start),
                text.getString(R.string.entity_sleep_end),
                text.getString(R.string.entity_watchdog),
                text.getString(R.string.entity_wifi_ssid),
                text.getString(R.string.entity_wifi_signal),
                text.getString(R.string.entity_cpu),
                text.getString(R.string.entity_memory),
                text.getString(R.string.entity_memory_low),
                text.getString(R.string.entity_storage),
                text.getString(R.string.entity_app_memory),
                text.getString(R.string.entity_temperature));
    }

    /** 发布截屏 JPEG，保留最后一张给 Home Assistant 摄像头实体。 */
    void publishScreenshot(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) return;
        worker.execute(() -> {
            if (client == null || !client.isConnected() || topics == null) return;
            try {
                MqttMessage message = new MqttMessage(jpeg);
                message.setQos(0);
                message.setRetained(true);
                client.publish(topics.camera(), message);
                Log.i(KioskApp.LOG, "MQTT screenshot published, bytes=" + jpeg.length);
            } catch (MqttException e) {
                Log.w(KioskApp.LOG, "MQTT screenshot failed: reason=" + e.getReasonCode());
            }
        });
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
            if (gen != generation) return;
            Log.w(KioskApp.LOG, "MQTT connection lost: " + (cause == null ? "unknown" : cause.getMessage()));
            setConnected(false);
            String endpoint = config == null ? "" : endpointLabel(config);
            hub.reportMqttLink(KioskHub.MqttLink.FAIL, endpoint, failureDetail(cause));
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
                case URL:
                    Log.i(KioskApp.LOG, "MQTT command: open url");
                    hub.requestOpenUrl(cmd.text);
                    break;
                case HOME:
                    Log.i(KioskApp.LOG, "MQTT command: home");
                    hub.requestHome();
                    break;
                case MOTION:
                    Log.i(KioskApp.LOG, "MQTT command: motion=" + cmd.value);
                    hub.requestMotion(cmd.value == 1);
                    break;
                case STANDBY_BRIGHTNESS:
                    Log.i(KioskApp.LOG, "MQTT command: standby brightness=" + cmd.value);
                    hub.requestStandbyBrightness(cmd.value);
                    break;
                case DIM_DELAY:
                    Log.i(KioskApp.LOG, "MQTT command: dim delay=" + cmd.value);
                    hub.requestDimDelay(cmd.value);
                    break;
                case LOCK:
                    Log.i(KioskApp.LOG, "MQTT command: lock task=" + cmd.value);
                    hub.requestLockTask(cmd.value == 1);
                    break;
                case SCREEN_OFF:
                    Log.i(KioskApp.LOG, "MQTT command: screen off");
                    hub.requestScreenOff();
                    break;
                case SCREENSHOT:
                    Log.i(KioskApp.LOG, "MQTT command: screenshot");
                    hub.requestScreenshot();
                    break;
                case NOTIFY:
                    Log.i(KioskApp.LOG, "MQTT command: notify");
                    hub.requestNotify(cmd.text);
                    break;
                case TTS:
                    Log.i(KioskApp.LOG, "MQTT command: speak");
                    hub.requestSpeak(cmd.text);
                    break;
                case SCHEDULE:
                    Log.i(KioskApp.LOG, "MQTT command: schedule=" + cmd.value);
                    hub.requestSchedule(cmd.value == 1);
                    break;
                case DAY_BRIGHTNESS:
                    Log.i(KioskApp.LOG, "MQTT command: day brightness=" + cmd.value);
                    hub.requestDayBrightness(cmd.value);
                    break;
                case NIGHT_BRIGHTNESS:
                    Log.i(KioskApp.LOG, "MQTT command: night brightness=" + cmd.value);
                    hub.requestNightBrightness(cmd.value);
                    break;
                case VOLUME:
                    Log.i(KioskApp.LOG, "MQTT command: volume=" + cmd.value);
                    hub.requestVolume(cmd.value);
                    break;
                case PLAY:
                    Log.i(KioskApp.LOG, "MQTT command: play audio");
                    hub.requestPlay(cmd.text);
                    break;
                case DAILY:
                    Log.i(KioskApp.LOG, "MQTT command: daily reload=" + cmd.value);
                    hub.requestDailyReload(cmd.value == 1);
                    break;
                case DAILY_HOUR:
                    Log.i(KioskApp.LOG, "MQTT command: daily hour=" + cmd.value);
                    hub.requestDailyHour(cmd.value);
                    break;
                case DAILY_MINUTE:
                    Log.i(KioskApp.LOG, "MQTT command: daily minute=" + cmd.value);
                    hub.requestDailyMinute(cmd.value);
                    break;
                case SLEEP:
                    Log.i(KioskApp.LOG, "MQTT command: sleep=" + cmd.value);
                    hub.requestSleep(cmd.value == 1);
                    break;
                case SLEEP_START:
                    Log.i(KioskApp.LOG, "MQTT command: sleep start=" + cmd.value);
                    hub.requestSleepStart(cmd.value);
                    break;
                case SLEEP_END:
                    Log.i(KioskApp.LOG, "MQTT command: sleep end=" + cmd.value);
                    hub.requestSleepEnd(cmd.value);
                    break;
                case WATCHDOG:
                    Log.i(KioskApp.LOG, "MQTT command: watchdog=" + cmd.value);
                    hub.requestWatchdog(cmd.value);
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
