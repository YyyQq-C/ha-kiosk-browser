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
        for (int i = 1; i < parts.length; i++) {
            long value;
            try {
                value = Long.parseLong(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            total += value;
            if (i == 4 || i == 5) idle += value;
        }
        return new CpuSample(total, idle);
    }

    /** 读取当前总 CPU 计数；读不到时返回 null。 */
    static CpuSample read() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            return parse(reader.readLine());
        } catch (IOException | SecurityException e) {
            return null;
        }
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
