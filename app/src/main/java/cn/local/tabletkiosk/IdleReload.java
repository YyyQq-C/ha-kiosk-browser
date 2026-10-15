package cn.local.tabletkiosk;

/** 无触摸达到设定小时数后触发一次刷新；小时数为 0 时关闭。 */
public final class IdleReload {
    private static final long HOUR_MS = 3_600_000L;
    private long intervalMs;
    private long lastInteraction;

    /**
     * @param hours 空闲小时数，0 表示关闭
     * @param now 当前单调时钟毫秒
     */
    public IdleReload(int hours, long now) {
        setHours(hours);
        lastInteraction = now;
    }

    /** 修改空闲小时数，不重置已累计的空闲时间。 */
    public void setHours(int hours) {
        intervalMs = Math.max(0, hours) * HOUR_MS;
    }

    /** 用户触摸，重新开始计时。 */
    public void onInteraction(long now) {
        lastInteraction = now;
    }

    /** 到点时返回 true 并重新计时，因此持续无人时每隔设定时长刷新一次。 */
    public boolean shouldReload(long now) {
        if (intervalMs <= 0) return false;
        if (now - lastInteraction < intervalMs) return false;
        lastInteraction = now;
        return true;
    }
}
