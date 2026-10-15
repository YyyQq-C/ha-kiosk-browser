package cn.local.tabletkiosk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 空闲刷新和观看/待机亮度切换的计时。 */
public class TimingTest {
    private static final long HOUR = 3_600_000L;

    /** 无触摸满设定小时后刷新一次，并重新计时。 */
    @Test
    public void idleReloadFiresAfterInterval() {
        IdleReload idle = new IdleReload(12, 0);
        assertFalse(idle.shouldReload(12 * HOUR - 1));
        assertTrue(idle.shouldReload(12 * HOUR));
        assertFalse(idle.shouldReload(12 * HOUR + 1000));
        assertTrue(idle.shouldReload(24 * HOUR));
    }

    /** 触摸重新开始计时。 */
    @Test
    public void interactionPostponesReload() {
        IdleReload idle = new IdleReload(1, 0);
        idle.onInteraction(HOUR - 10);
        assertFalse(idle.shouldReload(HOUR));
        assertTrue(idle.shouldReload(2 * HOUR - 10));
    }

    /** 小时数为 0 时关闭。 */
    @Test
    public void zeroHoursDisablesReload() {
        IdleReload idle = new IdleReload(0, 0);
        assertFalse(idle.shouldReload(10_000 * HOUR));
        idle.setHours(2);
        assertTrue(idle.shouldReload(10_000 * HOUR));
    }

    /** 无人达到设定秒数后降到待机亮度，触摸后恢复。 */
    @Test
    public void dimsAfterDelayAndWakesOnActivity() {
        PresenceTracker p = new PresenceTracker(60, 10, 0);
        assertFalse(p.tick(59_999));
        assertTrue(p.watching());
        assertTrue(p.tick(60_000));
        assertFalse(p.watching());
        assertTrue(p.onActivity(61_000));
        assertTrue(p.watching());
        assertFalse(p.onActivity(62_000));
    }

    /** 画面变化同时设置“有人靠近”，保持设定秒数后清除。 */
    @Test
    public void motionSetsPresenceForHoldTime() {
        PresenceTracker p = new PresenceTracker(60, 10, 0);
        p.tick(70_000);
        assertTrue(p.onMotion(70_000));
        assertTrue(p.presence());
        assertTrue(p.watching());
        assertFalse(p.tick(79_999));
        assertTrue(p.tick(80_000));
        assertFalse(p.presence());
        assertTrue(p.watching());
    }

    /** 延时为 0 时始终保持观看亮度。 */
    @Test
    public void zeroDelayAlwaysWatching() {
        PresenceTracker p = new PresenceTracker(0, 10, 0);
        assertFalse(p.tick(100 * HOUR));
        assertTrue(p.watching());
    }

    /** 关闭检测时清除“有人靠近”。 */
    @Test
    public void clearPresenceReportsChange() {
        PresenceTracker p = new PresenceTracker(60, 10, 0);
        p.onMotion(1000);
        assertTrue(p.clearPresence());
        assertFalse(p.clearPresence());
        assertFalse(p.presence());
    }
}
