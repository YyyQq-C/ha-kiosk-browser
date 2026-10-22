package cn.local.tabletkiosk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 按本地钟点选择观看亮度。 */
public class BrightnessScheduleTest {
    /** 未开启时间表时始终用观看亮度。 */
    @Test
    public void disabledUsesWatchBrightness() {
        KioskConfig c = KioskConfig.defaults();
        assertEquals(c.watchBrightness, BrightnessSchedule.watchingBrightness(c, 23));
    }

    /** 7 点到 22 点用白天亮度，其余用夜间亮度。 */
    @Test
    public void dayWindowAndOvernightWrap() {
        KioskConfig.Builder b = KioskConfig.defaults().toBuilder();
        b.scheduleEnabled = true;
        b.dayStartHour = 7;
        b.nightStartHour = 22;
        b.dayBrightness = 80;
        b.nightBrightness = 15;
        KioskConfig c = b.build();
        assertEquals(80, BrightnessSchedule.watchingBrightness(c, 7));
        assertEquals(80, BrightnessSchedule.watchingBrightness(c, 21));
        assertEquals(15, BrightnessSchedule.watchingBrightness(c, 22));
        assertEquals(15, BrightnessSchedule.watchingBrightness(c, 6));
        assertTrue(BrightnessSchedule.isDay(22, 7, 23));
        assertFalse(BrightnessSchedule.isDay(22, 7, 12));
        assertTrue(BrightnessSchedule.isDay(8, 8, 3));
    }
    /** 设置打开期间，即使观看计时被配置更新重算，也不能切到 0% 待机或屏保亮度。 */
    @org.junit.Test
    public void settingsHoldBrightnessAcrossTimerAndConfigUpdates() {
        KioskConfig.Builder builder = KioskConfig.defaults().toBuilder();
        builder.watchBrightness = 65;
        builder.standbyBrightness = 0;
        builder.screensaverBrightness = 0;
        builder.scheduleEnabled = false;
        KioskConfig config = builder.build();
        PresenceTracker presence = new PresenceTracker(1, 1, 0);
        for (int tick = 1; tick <= 10; tick++) {
            presence.tick(tick * 1000L);
            assertEquals(65, BrightnessSchedule.currentBrightness(config, 12, presence.watching(), true, true));
            presence.onActivity(tick * 1000L);
            assertEquals(65, BrightnessSchedule.currentBrightness(config, 12, presence.watching(), false, true));
        }
        // 离开设置后仍按原规则进入待机或屏保。
        assertEquals(0, BrightnessSchedule.currentBrightness(config, 12, false, false, false));
        assertEquals(0, BrightnessSchedule.currentBrightness(config, 12, false, true, false));
        builder.scheduleEnabled = true;
        builder.dayStartHour = 7;
        builder.nightStartHour = 20;
        builder.dayBrightness = 80;
        builder.nightBrightness = 35;
        assertEquals(80, BrightnessSchedule.currentBrightness(builder.build(), 12, false, false, true));
        assertEquals(35, BrightnessSchedule.currentBrightness(builder.build(), 22, false, false, true));
    }
}
