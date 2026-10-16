package cn.local.tabletkiosk;

/** 按本地钟点选择观看亮度。白天从开始时刻到夜间开始时刻，其余时间用夜间亮度。 */
public final class BrightnessSchedule {
    private BrightnessSchedule() {
    }

    /** 当前小时应使用的观看亮度；未开启时间表时用原来的观看亮度。 */
    public static int watchingBrightness(KioskConfig config, int hourOfDay) {
        if (!config.scheduleEnabled) return config.watchBrightness;
        return isDay(config.dayStartHour, config.nightStartHour, hourOfDay)
                ? config.dayBrightness : config.nightBrightness;
    }

    /** 判断这个小时是否落在白天。两个开始时刻相同时整天算白天。 */
    static boolean isDay(int dayStartHour, int nightStartHour, int hourOfDay) {
        int day = floorMod(dayStartHour, 24);
        int night = floorMod(nightStartHour, 24);
        int hour = floorMod(hourOfDay, 24);
        if (day == night) return true;
        if (day < night) return hour >= day && hour < night;
        return hour >= day || hour < night;
    }

    /** 把小时折进 0 到 23，负数也按 24 小时循环。 */
    private static int floorMod(int value, int mod) {
        int r = value % mod;
        return r < 0 ? r + mod : r;
    }
}
