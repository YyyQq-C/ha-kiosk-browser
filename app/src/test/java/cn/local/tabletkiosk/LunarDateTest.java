package cn.local.tabletkiosk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** 农历文字拼接。系统农历换算依赖设备 ICU，这里只验证干支和排版。 */
public class LunarDateTest {
    /** 2026 农历年是丙午。 */
    @Test
    public void stemBranchUsesSexagenaryCycle() {
        assertEquals("丙午", LunarDate.stemBranch(2026));
        assertEquals("甲子", LunarDate.stemBranch(1984));
        assertEquals("乙巳", LunarDate.stemBranch(2025));
    }

    /** 九月十一，以及闰月。 */
    @Test
    public void composeMonthAndDay() {
        assertEquals("丙午年九月十一", LunarDate.compose("丙午", 8, 11, false));
        assertEquals("癸卯年闰二月初一", LunarDate.compose("癸卯", 1, 1, true));
    }

    /** 月份或日期超出农历范围时不编造。 */
    @Test
    public void composeRejectsOutOfRange() {
        assertNull(LunarDate.compose("丙午", 12, 1, false));
        assertNull(LunarDate.compose("丙午", 0, 31, false));
    }
}
