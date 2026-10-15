package cn.local.tabletkiosk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 左边缘滑出设置的手势判定。 */
public class EdgeSwipeTest {
    private final EdgeSwipe swipe = new EdgeSwipe(24, 80, 12);

    /** 从边缘向右滑够距离时打开设置。 */
    @Test
    public void opensAfterRightSwipeFromEdge() {
        assertTrue(swipe.onDown(10, 300));
        swipe.onMove(60, 305);
        assertEquals(EdgeSwipe.Result.OPEN, swipe.onUp(120, 310));
        assertFalse(swipe.isTracking());
    }

    /** 起点不在边缘时不跟踪。 */
    @Test
    public void ignoresSwipeStartingAwayFromEdge() {
        assertFalse(swipe.onDown(40, 300));
        assertEquals(EdgeSwipe.Result.NONE, swipe.onUp(200, 300));
    }

    /** 距离不够不打开。 */
    @Test
    public void shortSwipeDoesNotOpen() {
        swipe.onDown(5, 300);
        assertEquals(EdgeSwipe.Result.NONE, swipe.onUp(60, 300));
    }

    /** 纵向位移大于横向时视为上下滚动，不打开。 */
    @Test
    public void mostlyVerticalSwipeDoesNotOpen() {
        swipe.onDown(5, 100);
        assertEquals(EdgeSwipe.Result.NONE, swipe.onUp(95, 300));
    }

    /** 几乎不动的轻点交还网页。 */
    @Test
    public void smallMovementIsTap() {
        swipe.onDown(8, 300);
        swipe.onMove(12, 305);
        assertEquals(EdgeSwipe.Result.TAP, swipe.onUp(10, 302));
        assertEquals(8f, swipe.downX(), 0f);
        assertEquals(300f, swipe.downY(), 0f);
    }

    /** 中途移动很远又回到原处，不算轻点。 */
    @Test
    public void largeMoveThenReturnIsNotTap() {
        swipe.onDown(8, 300);
        swipe.onMove(8, 400);
        assertEquals(EdgeSwipe.Result.NONE, swipe.onUp(8, 301));
    }

    /** 系统取消手势后不再跟踪。 */
    @Test
    public void cancelStopsTracking() {
        swipe.onDown(8, 300);
        swipe.cancel();
        assertEquals(EdgeSwipe.Result.NONE, swipe.onUp(200, 300));
    }
}
