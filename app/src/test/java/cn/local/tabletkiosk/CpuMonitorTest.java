package cn.local.tabletkiosk;

import static org.junit.Assert.assertEquals;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** 验证整机 CPU 读取降级、基线隔离和失败上报。 */
public class CpuMonitorTest {
    /** proc 无权限时使用系统整机计数，并对连续失败只记录一次。 */
    @Test
    public void fallsBackWhenProcDenied() {
        AtomicInteger clock = new AtomicInteger();
        AtomicInteger warnings = new AtomicInteger();
        CpuMonitor monitor = new CpuMonitor(() -> { throw new IOException("Permission denied"); },
                () -> new CpuSample[] {CpuSample.fromActive(50L * clock.incrementAndGet(), 100L * clock.get())},
                (message, error) -> warnings.incrementAndGet());
        assertEquals(-1, monitor.sample());
        assertEquals(50, monitor.sample());
        assertEquals(50, monitor.sample());
        assertEquals(1, warnings.get());
    }

    /** 来源恢复时重新采样，避免把不同单位的两个来源直接相减。 */
    @Test
    public void resetsBaselineWhenSourceChanges() {
        AtomicInteger tick = new AtomicInteger();
        CpuMonitor monitor = new CpuMonitor(() -> tick.get() < 2 ? null
                : new CpuSample[] {CpuSample.fromActive(500 + 10L * tick.get(), 1000 + 20L * tick.get())},
                () -> new CpuSample[] {CpuSample.fromActive(100 + 20L * tick.get(), 200 + 100L * tick.get())},
                (message, error) -> {});
        assertEquals(-1, monitor.sample());
        tick.incrementAndGet();
        assertEquals(20, monitor.sample());
        tick.incrementAndGet();
        assertEquals(-1, monitor.sample());
        tick.incrementAndGet();
        assertEquals(50, monitor.sample());
    }

    /** 两个来源都不可用时保持未知，不伪造 0%。 */
    @Test
    public void reportsUnavailableWithoutInventingUsage() {
        AtomicInteger warnings = new AtomicInteger();
        CpuMonitor monitor = new CpuMonitor(() -> null,
                () -> { throw new SecurityException("Not device owner"); },
                (message, error) -> warnings.incrementAndGet());
        assertEquals(-1, monitor.sample());
        assertEquals(-1, monitor.sample());
        assertEquals(2, warnings.get());
    }

    /** 核心上下线不把历史累计时间当成本次占用。 */
    @Test
    public void ignoresNewlyOnlineCoresUntilNextSample() {
        CpuSample[] before = {CpuSample.fromActive(100, 200), null};
        CpuSample[] after = {CpuSample.fromActive(120, 300), CpuSample.fromActive(9000, 10000)};
        assertEquals(20, CpuSample.percent(before, after));
    }

    /** guest 时间不能重复算入总量，非法系统计数不能参与采样。 */
    @Test
    public void validatesCountersAndExcludesGuestTime() {
        assertEquals(50, CpuSample.percent(CpuSample.parse("cpu 100 0 0 100 0 0 0 0 50 0"),
                CpuSample.parse("cpu 150 0 0 150 0 0 0 0 100 0")));
        org.junit.Assert.assertNull(CpuSample.fromActive(200, 100));
        org.junit.Assert.assertNull(CpuSample.parse("cpu -1 0 0 100"));
    }
}
