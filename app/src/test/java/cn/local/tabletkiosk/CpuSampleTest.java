package cn.local.tabletkiosk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** CPU 计数差和容量占用百分比。 */
public class CpuSampleTest {
    /** 空闲增加占一半时，CPU 占用是 50%。 */
    @Test
    public void cpuPercentFromDelta() {
        CpuSample previous = CpuSample.parse("cpu 100 0 50 800 50 0 0");
        CpuSample current = CpuSample.parse("cpu 150 0 100 850 100 0 0");
        assertEquals(50, CpuSample.percent(previous, current));
        assertEquals(-1, CpuSample.percent(null, current));
        assertEquals(-1, CpuSample.percent(current, previous));
    }

    /** 不是总 CPU 行或数字损坏时丢弃。 */
    @Test
    public void rejectsBadStatLine() {
        assertNull(CpuSample.parse(null));
        assertNull(CpuSample.parse("cpu0 1 2 3 4"));
        assertNull(CpuSample.parse("cpu a b c d"));
    }

    /** 已用 3GB、共 8GB 时约为 38%。 */
    @Test
    public void usedPercent() {
        assertEquals(38, CpuSample.usedPercent(8000, 5000));
        assertEquals(0, CpuSample.usedPercent(8000, 8000));
        assertEquals(100, CpuSample.usedPercent(8000, 0));
        assertEquals(-1, CpuSample.usedPercent(0, 0));
        assertEquals(-1, CpuSample.usedPercent(100, 101));
    }
}
