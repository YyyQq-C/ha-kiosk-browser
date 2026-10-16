package cn.local.tabletkiosk;

/** 每个本地日期在指定时分只触发一次刷新。 */
public final class DailyReload {
    private int lastDay = Integer.MIN_VALUE;

    /** 开关打开、时分吻合且这一天还没刷新过时返回 true，并记下这一天。 */
    public boolean due(boolean enabled, int hour, int minute, int nowHour, int nowMinute, int dayKey) {
        if (!enabled || nowHour != hour || nowMinute != minute || lastDay == dayKey) return false;
        lastDay = dayKey;
        return true;
    }
}
