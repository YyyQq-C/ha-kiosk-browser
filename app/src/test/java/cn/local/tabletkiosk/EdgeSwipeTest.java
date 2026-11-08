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
        assertEquals(EdgeSwipe.Result.OPEN_LEFT, swipe.onUp(120, 310));
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

    /** 未开启右边缘时，右侧起手不跟踪。 */
    @Test
    public void rightEdgeOffByDefault() {
        assertFalse(swipe.onDown(990, 300));
        assertEquals(EdgeSwipe.Result.NONE, swipe.onUp(850, 300));
    }

    /** 开启右边缘后，从右边缘向左滑够距离时打开应用栏。 */
    @Test
    public void opensRightAfterLeftSwipeFromRightEdge() {
        swipe.setRightEdge(1000);
        assertTrue(swipe.onDown(990, 300));
        swipe.onMove(940, 305);
        assertEquals(EdgeSwipe.Result.OPEN_RIGHT, swipe.onUp(880, 310));
        assertFalse(swipe.isTracking());
    }

    /** 右边缘向右或距离不够都不打开。 */
    @Test
    public void rightEdgeNeedsLeftwardDistance() {
        swipe.setRightEdge(1000);
        swipe.onDown(990, 300);
        assertEquals(EdgeSwipe.Result.NONE, swipe.onUp(940, 300));
        swipe.onDown(985, 300);
        assertEquals(EdgeSwipe.Result.NONE, swipe.onUp(1100, 300));
    }

    /** 右边缘纵向为主时视为滚动。 */
    @Test
    public void rightEdgeMostlyVerticalDoesNotOpen() {
        swipe.setRightEdge(1000);
        swipe.onDown(995, 100);
        assertEquals(EdgeSwipe.Result.NONE, swipe.onUp(905, 300));
    }

    /** 右边缘轻点也交还网页。 */
    @Test
    public void rightEdgeTapIsReplayed() {
        swipe.setRightEdge(1000);
        swipe.onDown(992, 300);
        assertEquals(EdgeSwipe.Result.TAP, swipe.onUp(990, 303));
        assertEquals(992f, swipe.downX(), 0f);
    }

    /** 左边缘向左滑不会被当成右边缘手势。 */
    @Test
    public void leftEdgeLeftwardSwipeDoesNotOpenRight() {
        swipe.setRightEdge(1000);
        swipe.onDown(10, 300);
        assertEquals(EdgeSwipe.Result.NONE, swipe.onUp(-200, 300));
    }

    /** 关掉右边缘后恢复只认左边缘。 */
    @Test
    public void rightEdgeCanBeTurnedOff() {
        swipe.setRightEdge(1000);
        swipe.setRightEdge(0);
        assertFalse(swipe.onDown(990, 300));
    }

    /** 系统取消手势后不再跟踪。 */
    @Test
    public void cancelStopsTracking() {
        swipe.onDown(8, 300);
        swipe.cancel();
        assertEquals(EdgeSwipe.Result.NONE, swipe.onUp(200, 300));
    }
}
