package cn.local.tabletkiosk;

import android.icu.util.Calendar;
import android.icu.util.TimeZone;
import android.icu.util.ULocale;
import java.time.LocalDate;
import java.time.ZoneId;

/** 把公历日期格式化成农历，使用系统自带的中国农历，不联网。 */
final class LunarDate {
    private static final String[] STEMS = {"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"};
    private static final String[] BRANCHES = {"子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"};
    private static final String[] MONTHS = {"正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊"};
    private static final String[] DAYS = {
            "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
            "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
    };

    private LunarDate() {
    }

    /** 按设备时区的当天中午转换。失败时返回 null，由界面显示占位。 */
    static String format(LocalDate date) {
        if (date == null) return null;
        try {
            long noon = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            Calendar calendar = Calendar.getInstance(TimeZone.getDefault(), new ULocale("zh_CN@calendar=chinese"));
            calendar.setTimeInMillis(noon);
            int month = calendar.get(Calendar.MONTH);
            int day = calendar.get(Calendar.DAY_OF_MONTH);
            // EXTENDED_YEAR 是连续年号，甲子按公元 4 年对齐；YEAR 在农历里可能只是干支循环内的序号
            int year = calendar.get(Calendar.EXTENDED_YEAR);
            boolean leap = calendar.get(Calendar.IS_LEAP_MONTH) == 1;
            return compose(stemBranch(year), month, day, leap);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 天干地支。甲子年按公元 4 年对齐，农历年号用系统给出的年份。 */
    static String stemBranch(int lunarYear) {
        int stem = Math.floorMod(lunarYear - 4, STEMS.length);
        int branch = Math.floorMod(lunarYear - 4, BRANCHES.length);
        return STEMS[stem] + BRANCHES[branch];
    }

    /** 拼成「丙午年九月十一」。月份从 0 起，日期从 1 起。无法识别时返回 null。 */
    static String compose(String yearName, int monthIndex, int day, boolean leap) {
        if (yearName == null || yearName.isEmpty()) return null;
        if (monthIndex < 0 || monthIndex >= MONTHS.length) return null;
        if (day < 1 || day > DAYS.length) return null;
        return yearName + "年" + (leap ? "闰" : "") + MONTHS[monthIndex] + "月" + DAYS[day - 1];
    }
}
