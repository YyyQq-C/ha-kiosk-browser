package cn.local.tabletkiosk;

/** 唤醒分数的连续命中、灵敏度和冷却。不跑模型。 */
public final class WakeWordGate {
    /** 连续超过阈值的次数，避免一声杂音就触发。 */
    public static final int HITS = 2;
    /** 触发后这段时间不再接受下一次。 */
    public static final long COOLDOWN_MS = 1500;

    private int streak;
    private long suppressUntil;

    /** 灵敏度 0 关闭；50 对应阈值 0.5；100 最容易触发。 */
    public static float threshold(int sensitivity) {
        if (sensitivity <= 0) return 1.1f;
        int value = Math.min(sensitivity, 100);
        return 0.8f - value * 0.006f;
    }

    /** 外部暂停，例如正在播放回答。 */
    public void pause(long until) {
        streak = 0;
        if (until > suppressUntil) suppressUntil = until;
    }

    /**
     * 送入一次分数。
     * paused 为真、还在冷却、或未连续命中时返回 false。
     */
    public boolean hear(float score, float threshold, long now, boolean paused) {
        if (paused || now < suppressUntil || score < threshold) {
            streak = 0;
            return false;
        }
        streak++;
        if (streak < HITS) return false;
        streak = 0;
        suppressUntil = now + COOLDOWN_MS;
        return true;
    }
}
