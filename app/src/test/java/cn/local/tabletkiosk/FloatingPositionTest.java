package cn.local.tabletkiosk;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/** 语音悬浮图标的边界、比例保存和横竖屏恢复。 */
public class FloatingPositionTest {
    /** 默认比例沿用右下角边距，超出坐标会限制到屏幕内。 */
    @Test
    public void clampsToVisibleBounds() {
        assertEquals(908f, FloatingPosition.coordinate(1f, 1000, 72, 20), 0.001f);
        assertEquals(20f, FloatingPosition.coordinate(0f, 1000, 72, 20), 0.001f);
        assertEquals(0f, FloatingPosition.relative(-100f, 1000, 72, 20), 0.001f);
        assertEquals(1f, FloatingPosition.relative(5000f, 1000, 72, 20), 0.001f);
    }

    /** 保存的位置比例在不同视口中仍能精确恢复。 */
    @Test
    public void restoresPositionAfterWindowResize() {
        float ratio = FloatingPosition.relative(360f, 1200, 90, 25);
        float restored = FloatingPosition.coordinate(ratio, 2000, 90, 25);
        assertEquals(ratio, FloatingPosition.relative(restored, 2000, 90, 25), 0.0001f);
        assertEquals(360f, FloatingPosition.coordinate(ratio, 1200, 90, 25), 0.001f);
    }

    /** 小窗口和非法比例不会产生负坐标或 NaN。 */
    @Test
    public void handlesSmallWindowsAndInvalidFractions() {
        assertEquals(0f, FloatingPosition.coordinate(1f, 50, 72, 20), 0.001f);
        assertEquals(4f, FloatingPosition.coordinate(1f, 80, 72, 20), 0.001f);
        assertEquals(1f, FloatingPosition.fraction(Float.NaN), 0.001f);
        assertEquals(1f, FloatingPosition.fraction(Float.POSITIVE_INFINITY), 0.001f);
    }
}
