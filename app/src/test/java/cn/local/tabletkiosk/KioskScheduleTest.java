package cn.local.tabletkiosk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 夜间熄屏时段、每日定时刷新和页面看门狗。 */
public class KioskScheduleTest {
    /** 23 点到次日 7 点算夜间，两端时刻相同则不熄屏。 */
    @Test
    public void sleepWindowWrapsMidnight() {
        assertTrue(SleepWindow.contains(23, 7, 23));
        assertTrue(SleepWindow.contains(23, 7, 0));
        assertFalse(SleepWindow.contains(23, 7, 7));
        assertFalse(SleepWindow.contains(23, 7, 12));
        assertTrue(SleepWindow.contains(1, 5, 1));
        assertFalse(SleepWindow.contains(1, 5, 5));
        assertFalse(SleepWindow.contains(8, 8, 8));
    }

    /** 同一分钟内只触发一次，第二天可以再触发。 */
    @Test
    public void dailyReloadFiresOncePerDay() {
        DailyReload reload = new DailyReload();
        assertFalse(reload.due(false, 3, 0, 3, 0, 2026289));
        assertTrue(reload.due(true, 3, 0, 3, 0, 2026289));
        assertFalse(reload.due(true, 3, 0, 3, 0, 2026289));
        assertFalse(reload.due(true, 3, 0, 3, 1, 2026289));
        assertTrue(reload.due(true, 3, 0, 3, 0, 2026290));
    }

    /** 加载超时和脚本无响应都会要求重载，0 秒表示关闭。 */
    @Test
    public void watchdogExpiresLoadAndScript() {
        PageWatchdog watchdog = new PageWatchdog();
        watchdog.onPageStarted(1_000);
        assertFalse(watchdog.expired(50_000, 60));
        assertTrue(watchdog.expired(61_000, 60));
        assertFalse(watchdog.expired(61_000, 0));

        watchdog.onPageFinished(70_000);
        assertFalse(watchdog.shouldPing(100_000, 60));
        assertTrue(watchdog.shouldPing(130_000, 60));
        watchdog.onPingSent(130_000);
        assertFalse(watchdog.expired(180_000, 60));
        assertTrue(watchdog.expired(190_000, 60));
        watchdog.onPingDone(190_000);
        assertFalse(watchdog.expired(200_000, 60));
    }
}
