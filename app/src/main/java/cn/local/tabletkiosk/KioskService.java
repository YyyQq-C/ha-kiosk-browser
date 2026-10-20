package cn.local.tabletkiosk;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.content.ContextCompat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** 前台服务：保持 MQTT、局域网接口和电池状态在后台持续运行。 */
public final class KioskService extends Service {
    private static final int NOTIFICATION_ID = 1;

    private KioskHub hub;
    private AssistRuntime assist;
    private MqttBridge mqtt;
    private LanServer lan;
    private ExecutorService lanWorker;
    private final Runnable statusListener = this::onStatusChanged;
    private final Runnable mqttSyncListener = this::onMqttSync;
    private final Consumer<KioskConfig> configListener = this::onConfigChanged;

    /** 电池广播：电量或充电状态变化时更新状态中心。 */
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBattery(intent);
        }
    };

    /** 创建时进入前台，并启动 MQTT 和局域网接口。 */
    @Override
    public void onCreate() {
        super.onCreate();
        enterForeground();
        hub = KioskHub.get();
        assist = AssistRuntime.start(this);
        mqtt = new MqttBridge(hub, this);
        hub.setImageSink(mqtt::publishScreenshot);
        lan = new LanServer(hub);
        lanWorker = Executors.newSingleThreadExecutor();

        // 注册电池广播；该广播为粘性广播，注册时会立即返回当前值
        Intent sticky = ContextCompat.registerReceiver(this, batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
        if (sticky != null) updateBattery(sticky);

        hub.addStatusListener(statusListener);
        hub.addConfigListener(configListener);
        hub.addMqttSyncListener(mqttSyncListener);
        onConfigChanged(hub.config());
        Log.i(KioskApp.LOG, "Kiosk service started");
    }

    /** 让通知文案跟随已保存的界面语言。 */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocales.wrap(newBase));
    }

    /** 被系统回收后自动重启。 */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        enterForeground();
        if (intent != null && AppLocales.ACTION_REFRESH.equals(intent.getAction()) && mqtt != null) {
            mqtt.republishDiscovery();
        }
        return START_STICKY;
    }

    /** 不提供绑定。 */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 停止时释放连接和监听。 */
    @Override
    public void onDestroy() {
        hub.removeStatusListener(statusListener);
        hub.removeConfigListener(configListener);
        hub.removeMqttSyncListener(mqttSyncListener);
        try {
            unregisterReceiver(batteryReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(KioskApp.LOG, "Battery receiver was not registered", e);
        }
        if (assist != null) assist.shutdown();
        mqtt.shutdown();
        lanWorker.execute(lan::stop);
        lanWorker.shutdown();
        Log.i(KioskApp.LOG, "Kiosk service stopped");
        super.onDestroy();
    }

    /** 显示常驻通知并进入前台；Android 14 起需要声明服务类型。 */
    private void enterForeground() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Context localized = AppLocales.wrap(this);
        Notification notification = new Notification.Builder(this, KioskApp.CHANNEL)
                .setSmallIcon(R.drawable.ic_kiosk)
                .setContentTitle(localized.getString(R.string.app_name))
                .setContentText(localized.getString(R.string.notification_text))
                .setContentIntent(content)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            if (cameraInForeground()) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if (microphoneInForeground()) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    /** 语音助手已开且有麦克风权限时，前台服务声明麦克风类型，息屏后仍能听唤醒词。 */
    private boolean microphoneInForeground() {
        KioskConfig config = hub == null ? null : hub.config();
        return config != null && config.assistEnabled
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** 人体检测已开且有摄像头权限时，前台服务声明摄像头类型，息屏后才能继续检测。 */
    private boolean cameraInForeground() {
        KioskConfig config = hub == null ? null : hub.config();
        return config != null && config.motionEnabled && config.motionSensitivity > 0
                && checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    /** 从电池广播读取电量和充电状态。 */
    private void updateBattery(Intent intent) {
        BatteryState battery = BatteryState.from(
                intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN));
        hub.updateStatus(b -> {
            b.battery = battery.percent;
            b.charging = battery.charging;
            b.chargeState = battery.state;
            b.batteryTempTenths = intent.hasExtra(BatteryManager.EXTRA_TEMPERATURE)
                    ? intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1000) : -1000;
            return b;
        });
    }

    /** 状态变化时立即通过 MQTT 上报。 */
    private void onStatusChanged() {
        mqtt.publishState();
    }

    /** 保存按钮要求立刻按当前配置连接并上报。 */
    private void onMqttSync() {
        mqtt.syncNow();
    }

    /** 配置变化时更新 MQTT 和局域网接口；端口绑定放到后台线程。 */
    private void onConfigChanged(KioskConfig config) {
        mqtt.apply(config);
        lanWorker.execute(() -> lan.apply(config));
        if (assist != null) assist.apply();
        enterForeground();
    }
}
