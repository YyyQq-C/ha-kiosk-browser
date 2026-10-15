package cn.local.tabletkiosk;

/**
 * 管理观看亮度和待机亮度的切换，以及“有人靠近”状态。
 * 触摸或画面变化升到观看亮度；连续无人达到设定秒数后降到待机亮度。
 */
public final class PresenceTracker {
    private long dimDelayMs;
    private long holdMs;
    private boolean watching = true;
    private boolean presence;
    private long lastActivity;
    private long lastMotion;

    /**
     * @param dimDelaySeconds 无人后降亮度的秒数，0 表示始终保持观看亮度
     * @param holdSeconds 一次画面变化后“有人靠近”保持的秒数
     * @param now 当前单调时钟毫秒
     */
    public PresenceTracker(int dimDelaySeconds, int holdSeconds, long now) {
        configure(dimDelaySeconds, holdSeconds);
        lastActivity = now;
    }

    /** 修改延时参数，立即在下一次 tick 生效。 */
    public void configure(int dimDelaySeconds, int holdSeconds) {
        dimDelayMs = Math.max(0, dimDelaySeconds) * 1000L;
        holdMs = Math.max(1, holdSeconds) * 1000L;
    }

    /** 触摸或远程唤醒：升到观看亮度并重置无人计时；状态有变化时返回 true。 */
    public boolean onActivity(long now) {
        lastActivity = now;
        boolean changed = !watching;
        watching = true;
        return changed;
    }

    /** 摄像头检测到画面变化：标记有人，并升到观看亮度；状态有变化时返回 true。 */
    public boolean onMotion(long now) {
        lastMotion = now;
        boolean changed = !presence;
        presence = true;
        return onActivity(now) || changed;
    }

    /** 关闭人体检测时清除“有人靠近”；状态有变化时返回 true。 */
    public boolean clearPresence() {
        boolean changed = presence;
        presence = false;
        return changed;
    }

    /** 定时检查是否该降亮度或结束“有人”状态；状态有变化时返回 true。 */
    public boolean tick(long now) {
        boolean changed = false;
        boolean shouldWatch = dimDelayMs <= 0 || now - lastActivity < dimDelayMs;
        if (shouldWatch != watching) {
            watching = shouldWatch;
            changed = true;
        }
        if (presence && now - lastMotion >= holdMs) {
            presence = false;
            changed = true;
        }
        return changed;
    }

    /** 当前是否处于观看亮度。 */
    public boolean watching() {
        return watching;
    }

    /** 当前是否判定有人靠近。 */
    public boolean presence() {
        return presence;
    }
}
