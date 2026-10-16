package cn.local.tabletkiosk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** 应用内唯一的状态中心：保存配置与运行状态，并把远程命令转到主线程执行。 */
public final class KioskHub {
    /** 主界面实现的控制接口，只在主线程调用。 */
    public interface Controller {
        /** 清缓存并重载当前页。 */
        void reload();

        /** 立即升到观看亮度，并在屏幕熄灭时点亮。 */
        void wake();

        /** 打开指定网页，不改已保存的首页。 */
        void openUrl(String url);

        /** 回到已保存的首页。 */
        void goHome();

        /** 熄灭屏幕。只在设备所有者下有效。 */
        void screenOff();

        /** 截取当前画面。 */
        void captureScreen();

        /** 在画面上方显示一条通知。 */
        void showNotice(String text);

        /** 朗读一段文字。 */
        void speak(String text);

        /** 播放一个 http 或 https 音频地址。 */
        void playAudio(String url);
    }

    private static volatile KioskHub instance;

    private final Context app;
    private final KioskConfigStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> statusListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<KioskConfig>> configListeners = new CopyOnWriteArrayList<>();
    private volatile KioskConfig config;
    private volatile KioskStatus status = KioskStatus.initial();
    private volatile boolean reopenSettings;
    private Controller controller;
    private java.util.function.Consumer<byte[]> imageSink;

    /** 读取已保存配置并记录设备所有者状态。 */
    private KioskHub(Context context) {
        app = context.getApplicationContext();
        store = new KioskConfigStore(app);
        config = store.load();
        KioskStatus.Builder b = status.toBuilder();
        b.deviceOwner = DeviceOwnerPolicy.isDeviceOwner(app);
        status = b.build();
    }

    /** 在 Application 创建时初始化。 */
    static synchronized void init(Context context) {
        if (instance == null) instance = new KioskHub(context);
    }

    /** 获取单例；必须在 KioskApp.onCreate 之后调用。 */
    public static KioskHub get() {
        KioskHub hub = instance;
        if (hub == null) throw new IllegalStateException("KioskHub is not initialized");
        return hub;
    }

    /** 当前配置快照。 */
    public KioskConfig config() {
        return config;
    }

    /** 当前运行状态快照。 */
    public KioskStatus status() {
        return status;
    }

    /** 本机设备编号。 */
    public String deviceId() {
        return store.deviceId();
    }

    /** 配置存储，供首次运行提示使用。 */
    public KioskConfigStore store() {
        return store;
    }

    /** 界面重建后重新打开设置。设置里切换语言不会设置这个标记。 */
    public void requestReopenSettings() {
        reopenSettings = true;
    }

    /** 取出并清除“重新打开设置”标记。 */
    public boolean consumeReopenSettings() {
        boolean open = reopenSettings;
        reopenSettings = false;
        return open;
    }

    /** 保存新配置并在主线程通知所有监听者。 */
    public void updateConfig(KioskConfig next) {
        if (next == null) return;
        store.save(next);
        config = next;
        runOnMain(() -> {
            for (Consumer<KioskConfig> listener : configListeners) listener.accept(next);
        });
    }

    /** 修改运行状态；只有内容变化时才通知，避免频繁上报。 */
    public void updateStatus(UnaryOperator<KioskStatus.Builder> change) {
        boolean changed;
        synchronized (this) {
            KioskStatus before = status;
            KioskStatus after = change.apply(before.toBuilder()).build();
            changed = !sameStatus(before, after);
            if (changed) status = after;
        }
        if (changed) runOnMain(() -> {
            for (Runnable listener : statusListeners) listener.run();
        });
    }

    /** 注册状态变化监听，回调在主线程。 */
    public void addStatusListener(Runnable listener) {
        statusListeners.add(listener);
    }

    /** 取消状态变化监听。 */
    public void removeStatusListener(Runnable listener) {
        statusListeners.remove(listener);
    }

    /** 注册配置变化监听，回调在主线程。 */
    public void addConfigListener(Consumer<KioskConfig> listener) {
        configListeners.add(listener);
    }

    /** 取消配置变化监听。 */
    public void removeConfigListener(Consumer<KioskConfig> listener) {
        configListeners.remove(listener);
    }

    /** 主界面创建和销毁时设置控制器。 */
    public void setController(Controller next) {
        controller = next;
    }

    /** 前台服务注册截屏发布回调。 */
    public void setImageSink(java.util.function.Consumer<byte[]> sink) {
        imageSink = sink;
    }

    /** 把截屏交给 MQTT 发布。 */
    public void submitScreenshot(byte[] jpeg) {
        java.util.function.Consumer<byte[]> sink = imageSink;
        if (sink != null) sink.accept(jpeg);
    }

    /** 远程刷新命令：转到主线程执行。 */
    public void requestReload() {
        runOnMain(() -> {
            if (controller != null) controller.reload();
            else Log.w(KioskApp.LOG, "Reload requested while dashboard is not visible");
        });
    }

    /** 远程唤醒命令：转到主线程执行。 */
    public void requestWake() {
        runOnMain(() -> {
            if (controller != null) controller.wake();
            else Log.w(KioskApp.LOG, "Wake requested while dashboard is not visible");
        });
    }

    /** 远程设置观看亮度：保存后由配置监听立即应用。 */
    public void requestWatchBrightness(int percent) {
        patchConfig(b -> b.watchBrightness = clamp(percent, 1, 100));
    }

    /** 打开指定网页。 */
    public void requestOpenUrl(String url) {
        runOnMain(() -> {
            if (controller != null) controller.openUrl(url);
        });
    }

    /** 回到首页。 */
    public void requestHome() {
        runOnMain(() -> {
            if (controller != null) controller.goHome();
        });
    }

    /** 开关人体检测。 */
    public void requestMotion(boolean enabled) {
        patchConfig(b -> b.motionEnabled = enabled);
    }

    /** 设置待机亮度，0 为最暗。 */
    public void requestStandbyBrightness(int percent) {
        patchConfig(b -> b.standbyBrightness = clamp(percent, 0, 100));
    }

    /** 设置无人后降亮度的秒数，0 表示保持观看亮度。 */
    public void requestDimDelay(int seconds) {
        patchConfig(b -> b.dimDelaySeconds = clamp(seconds, 0, 86400));
    }

    /** 开关锁定模式。不是设备所有者时配置会保存，但不会进入锁定。 */
    public void requestLockTask(boolean enabled) {
        patchConfig(b -> b.lockTaskEnabled = enabled);
    }

    /** 熄灭屏幕。 */
    public void requestScreenOff() {
        runOnMain(() -> {
            if (controller != null) controller.screenOff();
        });
    }

    /** 截取当前画面并发布。 */
    public void requestScreenshot() {
        runOnMain(() -> {
            if (controller != null) controller.captureScreen();
        });
    }

    /** 在画面上显示通知。 */
    public void requestNotify(String text) {
        runOnMain(() -> {
            if (controller != null) controller.showNotice(text);
        });
    }

    /** 语音播报。 */
    public void requestSpeak(String text) {
        runOnMain(() -> {
            if (controller != null) controller.speak(text);
        });
    }

    /** 开关按时间切换亮度。 */
    public void requestSchedule(boolean enabled) {
        patchConfig(b -> b.scheduleEnabled = enabled);
    }

    /** 设置白天观看亮度。 */
    public void requestDayBrightness(int percent) {
        patchConfig(b -> b.dayBrightness = clamp(percent, 1, 100));
    }

    /** 设置夜间观看亮度。 */
    public void requestNightBrightness(int percent) {
        patchConfig(b -> b.nightBrightness = clamp(percent, 1, 100));
    }

    /** 设置媒体音量百分比。 */
    public void requestVolume(int percent) {
        patchConfig(b -> b.volume = clamp(percent, 0, 100));
    }

    /** 播放音频地址。 */
    public void requestPlay(String url) {
        runOnMain(() -> {
            if (controller != null) controller.playAudio(url);
        });
    }

    /** 开关每天固定时刻刷新。 */
    public void requestDailyReload(boolean enabled) {
        patchConfig(b -> b.dailyReloadEnabled = enabled);
    }

    /** 设置每天刷新的小时。 */
    public void requestDailyHour(int hour) {
        patchConfig(b -> b.dailyReloadHour = clamp(hour, 0, 23));
    }

    /** 设置每天刷新的分钟。 */
    public void requestDailyMinute(int minute) {
        patchConfig(b -> b.dailyReloadMinute = clamp(minute, 0, 59));
    }

    /** 开关夜间熄屏。 */
    public void requestSleep(boolean enabled) {
        patchConfig(b -> b.sleepEnabled = enabled);
    }

    /** 设置夜间熄屏开始小时。 */
    public void requestSleepStart(int hour) {
        patchConfig(b -> b.sleepStartHour = clamp(hour, 0, 23));
    }

    /** 设置夜间熄屏结束小时。 */
    public void requestSleepEnd(int hour) {
        patchConfig(b -> b.sleepEndHour = clamp(hour, 0, 23));
    }

    /** 设置页面无响应后重载的秒数，0 为关闭。 */
    public void requestWatchdog(int seconds) {
        patchConfig(b -> b.watchdogSeconds = clamp(seconds, 0, 3600));
    }

    /** 修改配置中的一项；没有变化时不保存、不通知。 */
    private void patchConfig(java.util.function.Consumer<KioskConfig.Builder> change) {
        KioskConfig.Builder builder = config.toBuilder();
        change.accept(builder);
        KioskConfig next = builder.build();
        if (!next.equals(config)) updateConfig(next);
    }

    /** 把整数限制在闭区间内。 */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 远程重启平板，只在设备所有者模式下可用。 */
    public boolean requestReboot() {
        return DeviceOwnerPolicy.reboot(app);
    }

    /** 在主线程执行，已在主线程时直接运行。 */
    private void runOnMain(Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) task.run();
        else main.post(task);
    }

    /** 比较两个状态快照的全部字段。 */
    private static boolean sameStatus(KioskStatus a, KioskStatus b) {
        return a.url.equals(b.url) && a.brightness == b.brightness && a.watching == b.watching
                && a.interactive == b.interactive && a.presence == b.presence && a.battery == b.battery
                && a.charging == b.charging && a.chargeState.equals(b.chargeState)
                && a.mqttConnected == b.mqttConnected && a.deviceOwner == b.deviceOwner
                && a.lockTask == b.lockTask && a.ip.equals(b.ip) && a.appVersion.equals(b.appVersion)
                && a.model.equals(b.model) && a.androidVersion.equals(b.androidVersion)
                && a.volume == b.volume && a.wifiSsid.equals(b.wifiSsid) && a.wifiRssi == b.wifiRssi
                && a.cpuPercent == b.cpuPercent && a.memoryPercent == b.memoryPercent
                && a.memoryUsedMb == b.memoryUsedMb && a.memoryTotalMb == b.memoryTotalMb
                && a.memoryLow == b.memoryLow && a.storagePercent == b.storagePercent
                && a.appMemoryMb == b.appMemoryMb && a.batteryTempTenths == b.batteryTempTenths;
    }
}
