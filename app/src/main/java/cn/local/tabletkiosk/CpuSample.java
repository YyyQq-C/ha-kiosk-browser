package cn.local.tabletkiosk;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/** 把 /proc/stat 的两次采样换成 CPU 占用百分比，也计算内存和存储占用。 */
public final class CpuSample {
    final long total;
    final long idle;

    /** 保存总时间片和空闲时间片。 */
    CpuSample(long total, long idle) {
        this.total = total;
        this.idle = idle;
    }

    /** 解析 /proc/stat 的总 CPU 行。idle 和 iowait 都算空闲。 */
    public static CpuSample parse(String line) {
        if (line == null) return null;
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 5 || !"cpu".equals(parts[0])) return null;
        long total = 0;
        long idle = 0;
        // guest 和 guest_nice 已包含在 user、nice 中，不能再次累加。
        for (int i = 1; i < Math.min(parts.length, 9); i++) {
            long value;
            try {
                value = Long.parseLong(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (value < 0 || Long.MAX_VALUE - total < value) return null;
            total += value;
            if (i == 4 || i == 5) idle += value;
        }
        return new CpuSample(total, idle);
    }

    /** 读取整机 CPU 计数；让采样器记录读取异常并选择备用来源。 */
    static CpuSample read() throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            return parse(reader.readLine());
        }
    }

    /** 从系统接口的活跃时间和总时间生成同一语义的计数。 */
    static CpuSample fromActive(long active, long total) {
        if (active < 0 || total <= 0 || active > total) return null;
        return new CpuSample(total, total - active);
    }

    /** 只累计前后两次都在线的核心增量，避免核心上下线造成占用跳变。 */
    static int percent(CpuSample[] previous, CpuSample[] current) {
        if (previous == null || current == null) return -1;
        long total = 0;
        long idle = 0;
        for (int i = 0; i < Math.min(previous.length, current.length); i++) {
            CpuSample before = previous[i];
            CpuSample after = current[i];
            if (before == null || after == null) continue;
            long delta = after.total - before.total;
            long idleDelta = after.idle - before.idle;
            if (delta <= 0 || idleDelta < 0 || idleDelta > delta) continue;
            total += delta;
            idle += idleDelta;
        }
        return total > 0 ? (int) Math.round((total - idle) * 100.0 / total) : -1;
    }

    /** 两次采样之间的占用百分比。间隔无效时返回 -1。 */
    public static int percent(CpuSample previous, CpuSample current) {
        if (previous == null || current == null) return -1;
        long totalDelta = current.total - previous.total;
        long idleDelta = current.idle - previous.idle;
        if (totalDelta <= 0 || idleDelta < 0 || idleDelta > totalDelta) return -1;
        return (int) Math.round((totalDelta - idleDelta) * 100.0 / totalDelta);
    }

    /** 已用容量占总量的百分比。总量无效时返回 -1。 */
    public static int usedPercent(long total, long available) {
        if (total <= 0 || available < 0 || available > total) return -1;
        return (int) Math.round((total - available) * 100.0 / total);
    }
}
