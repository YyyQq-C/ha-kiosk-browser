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
    }

    private static volatile KioskHub instance;

    private final Context app;
    private final KioskConfigStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> statusListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<KioskConfig>> configListeners = new CopyOnWriteArrayList<>();
    private volatile KioskConfig config;
    private volatile KioskStatus status = KioskStatus.initial();
    private Controller controller;

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
        int clamped = Math.max(1, Math.min(100, percent));
        KioskConfig current = config;
        if (current.watchBrightness == clamped) return;
        KioskConfig.Builder b = current.toBuilder();
        b.watchBrightness = clamped;
        updateConfig(b.build());
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
                && a.lockTask == b.lockTask;
    }
}
