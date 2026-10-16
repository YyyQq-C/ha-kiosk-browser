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
}
