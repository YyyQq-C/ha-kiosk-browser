package cn.local.tabletkiosk;

/** 判断当前小时是否落在夜间熄屏时段。开始和结束相同时不熄屏。 */
public final class SleepWindow {
    private SleepWindow() {
    }

    /** 含开始时刻，不含结束时刻；开始晚于结束时时段跨过午夜。 */
    public static boolean contains(int startHour, int endHour, int hourOfDay) {
        int start = floorMod(startHour, 24);
        int end = floorMod(endHour, 24);
        int hour = floorMod(hourOfDay, 24);
        if (start == end) return false;
        if (start < end) return hour >= start && hour < end;
        return hour >= start || hour < end;
    }

    /** 把小时折进 0 到 23。 */
    private static int floorMod(int value, int mod) {
        int r = value % mod;
        return r < 0 ? r + mod : r;
    }
}
