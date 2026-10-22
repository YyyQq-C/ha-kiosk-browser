package cn.local.tabletkiosk;

import android.content.Context;
import android.os.CpuUsageInfo;
import android.os.HardwarePropertiesManager;
import android.util.Log;
import java.util.function.BiConsumer;

/** 读取整机 CPU，支持受限设备的系统接口备用来源，不用进程占用冒充整机占用。 */
final class CpuMonitor {
    /** 一个来源返回总计数或逐核心计数，离线核心保留 null。 */
    interface Reader {
        /** 读取当前计数，权限或读取异常交给采样器处理。 */
        CpuSample[] read() throws Exception;
    }

    private final Reader[] readers;
    private final boolean[] failed = new boolean[2];
    private final BiConsumer<String, Exception> logger;
    private CpuSample[] previous;
    private int previousSource = -1;

    /** 注入读取器，便于使用模拟来源验证权限拒绝、恢复和来源切换。 */
    CpuMonitor(Reader proc, Reader hardware, BiConsumer<String, Exception> logger) {
        readers = new Reader[] {proc, hardware};
        this.logger = logger;
    }

    /** 创建设备采样器；系统接口要求设备所有者权限，并依赖厂商支持。 */
    static CpuMonitor create(Context context) {
        HardwarePropertiesManager hardware = context.getSystemService(HardwarePropertiesManager.class);
        return new CpuMonitor(() -> new CpuSample[] {CpuSample.read()}, () -> {
            if (hardware == null) return null;
            CpuUsageInfo[] usages = hardware.getCpuUsages();
            CpuSample[] samples = new CpuSample[usages.length];
            for (int i = 0; i < usages.length; i++) {
                if (usages[i] != null) samples[i] = CpuSample.fromActive(usages[i].getActive(), usages[i].getTotal());
            }
            return samples;
        }, (message, error) -> {
            if (error == null) Log.w(KioskApp.LOG, message);
            else Log.w(KioskApp.LOG, message, error);
        });
    }

    /** 每次采样优先读取 proc，失败后尝试系统接口；切换来源必须重新建立基线。 */
    int sample() {
        for (int source = 0; source < readers.length; source++) {
            CpuSample[] current;
            try {
                current = readers[source].read();
                if (!available(current)) {
                    report(source, null);
                    continue;
                }
            } catch (Exception e) {
                report(source, e);
                continue;
            }
            failed[source] = false;
            int percent = previousSource == source ? CpuSample.percent(previous, current) : -1;
            previous = current;
            previousSource = source;
            return percent;
        }
        // 本次失败不能继续展示上一次的占用，恢复后需要重新采样。
        previous = null;
        previousSource = -1;
        return -1;
    }

    /** 判断是否至少取得一个有效核心。 */
    private static boolean available(CpuSample[] samples) {
        if (samples == null) return false;
        for (CpuSample sample : samples) if (sample != null) return true;
        return false;
    }

    /** 每个来源连续失败只记录一次，恢复后再次失败仍会记录。 */
    private void report(int source, Exception error) {
        if (!failed[source]) logger.accept("Device CPU source unavailable: "
                + (source == 0 ? "proc_stat" : "hardware_properties"), error);
        failed[source] = true;
    }
}
