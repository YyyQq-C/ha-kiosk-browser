package cn.local.tabletkiosk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

/** 亮度网格画面变化判断。 */
public class MotionDetectorTest {
    private static final int CELLS = 32 * 24;

    /** 生成全部格子相同亮度的一帧。 */
    private static int[] flat(int value) {
        int[] cells = new int[CELLS];
        Arrays.fill(cells, value);
        return cells;
    }

    /** 第一帧只作为参照。 */
    @Test
    public void firstFrameNeverTriggers() {
        assertFalse(new MotionDetector(50).process(flat(100)));
    }

    /** 大面积变化触发。 */
    @Test
    public void largeLocalChangeTriggers() {
        MotionDetector d = new MotionDetector(50);
        d.process(flat(100));
        int[] next = flat(100);
        for (int i = 0; i < CELLS / 5; i++) next[i] = 200;
        assertTrue(d.process(next));
    }

    /** 整体变亮（开灯、自动曝光）被扣除，不触发。 */
    @Test
    public void globalBrightnessShiftIgnored() {
        MotionDetector d = new MotionDetector(100);
        d.process(flat(80));
        assertFalse(d.process(flat(160)));
    }

    /** 少量噪声不触发。 */
    @Test
    public void smallNoiseIgnored() {
        MotionDetector d = new MotionDetector(100);
        d.process(flat(100));
        int[] next = flat(100);
        for (int i = 0; i < CELLS; i += 2) next[i] = 110;
        assertFalse(d.process(next));
    }

    /** 灵敏度为 0 时关闭。 */
    @Test
    public void zeroSensitivityDisables() {
        MotionDetector d = new MotionDetector(0);
        d.process(flat(0));
        assertFalse(d.process(flat(255)));
        int[] half = flat(0);
        for (int i = 0; i < CELLS / 2; i++) half[i] = 255;
        assertFalse(d.process(half));
    }

    /** 灵敏度越高，所需变化格子比例越低。 */
    @Test
    public void thresholdScalesWithSensitivity() {
        assertEquals(0.20, MotionDetector.changedFractionThreshold(1), 1e-9);
        assertEquals(0.005, MotionDetector.changedFractionThreshold(100), 1e-9);
        assertTrue(MotionDetector.changedFractionThreshold(30) > MotionDetector.changedFractionThreshold(70));
    }

    /** 少量格子变化：高灵敏度触发，低灵敏度不触发。 */
    @Test
    public void sensitivityChangesOutcome() {
        int[] next = flat(100);
        for (int i = 0; i < CELLS / 20; i++) next[i] = 250;
        MotionDetector high = new MotionDetector(100);
        high.process(flat(100));
        assertTrue(high.process(next));
        MotionDetector low = new MotionDetector(1);
        low.process(flat(100));
        assertFalse(low.process(next));
    }

    /** reset 后下一帧重新作为参照。 */
    @Test
    public void resetDropsReference() {
        MotionDetector d = new MotionDetector(100);
        d.process(flat(0));
        d.reset();
        int[] half = flat(0);
        for (int i = 0; i < CELLS / 2; i++) half[i] = 255;
        assertFalse(d.process(half));
    }

    /** Y 平面按行跨度和像素间隔缩成网格。 */
    @Test
    public void downsampleAveragesQuadrants() {
        int width = 8;
        int height = 4;
        int rowStride = 10;
        byte[] y = new byte[rowStride * height];
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                y[row * rowStride + col] = (byte) (col < width / 2 ? 40 : 200);
            }
            // 行尾填充字节不应参与计算
            y[row * rowStride + 8] = (byte) 255;
            y[row * rowStride + 9] = (byte) 255;
        }
        int[] cells = MotionDetector.downsample(y, width, height, rowStride, 1, 2, 2);
        assertArrayEquals(new int[] {40, 200, 40, 200}, cells);
    }
}
