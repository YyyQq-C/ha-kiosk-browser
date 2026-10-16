package cn.local.tabletkiosk;

/** 页面长时间打不开，或打开后脚本不再响应时，要求重新加载。秒数为 0 时关闭。 */
public final class PageWatchdog {
    private long loadStartedAt;
    private long pingSentAt;
    private long lastPingAt;
    private boolean loaded;

    /** 开始加载，取消上一轮探测。 */
    public void onPageStarted(long now) {
        loaded = false;
        loadStartedAt = now;
        pingSentAt = 0;
    }

    /** 加载成功，从现在开始计算脚本探测间隔。 */
    public void onPageFinished(long now) {
        loaded = true;
        loadStartedAt = 0;
        pingSentAt = 0;
        lastPingAt = now;
    }

    /** 加载或脚本响应超过设定秒数。 */
    public boolean expired(long now, int timeoutSeconds) {
        if (timeoutSeconds <= 0) return false;
        long limit = timeoutSeconds * 1000L;
        if (!loaded && loadStartedAt > 0 && now - loadStartedAt >= limit) return true;
        return loaded && pingSentAt > 0 && now - pingSentAt >= limit;
    }

    /** 页面已打开，且距离上次探测已达到间隔。 */
    public boolean shouldPing(long now, int timeoutSeconds) {
        if (timeoutSeconds <= 0 || !loaded || pingSentAt > 0) return false;
        return now - lastPingAt >= timeoutSeconds * 1000L;
    }

    /** 记录已向页面发出探测。 */
    public void onPingSent(long now) {
        pingSentAt = now;
    }

    /** 页面返回了探测结果。 */
    public void onPingDone(long now) {
        pingSentAt = 0;
        lastPingAt = now;
    }
}
