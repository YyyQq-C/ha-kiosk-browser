package cn.local.tabletkiosk;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

/** 读取本机内存、存储和应用自身占用。不需要 root。 */
final class SystemPressure {
    final int memoryPercent;
    final int memoryUsedMb;
    final int memoryTotalMb;
    final boolean memoryLow;
    final int storagePercent;
    final int appMemoryMb;

    /** 保存一次读取结果，读不到的项为 -1。 */
    SystemPressure(int memoryPercent, int memoryUsedMb, int memoryTotalMb, boolean memoryLow,
                   int storagePercent, int appMemoryMb) {
        this.memoryPercent = memoryPercent;
        this.memoryUsedMb = memoryUsedMb;
        this.memoryTotalMb = memoryTotalMb;
        this.memoryLow = memoryLow;
        this.storagePercent = storagePercent;
        this.appMemoryMb = appMemoryMb;
    }

    /** 读取当前系统压力。单项失败时只让该项保持未知。 */
    static SystemPressure read(Context context) {
        int memoryPercent = -1;
        int memoryUsedMb = -1;
        int memoryTotalMb = -1;
        boolean memoryLow = false;
        int storagePercent = -1;
        int appMemoryMb = -1;
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager != null) {
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                manager.getMemoryInfo(info);
                memoryPercent = CpuSample.usedPercent(info.totalMem, info.availMem);
                memoryTotalMb = (int) (info.totalMem / (1024 * 1024));
                memoryUsedMb = (int) ((info.totalMem - info.availMem) / (1024 * 1024));
                memoryLow = info.lowMemory;
            }
        } catch (RuntimeException e) {
            Log.w(KioskApp.LOG, "Memory status unavailable", e);
        }
        try {
            StatFs stats = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            storagePercent = CpuSample.usedPercent(stats.getTotalBytes(), stats.getAvailableBytes());
        } catch (RuntimeException e) {
            Log.w(KioskApp.LOG, "Storage status unavailable", e);
        }
        try {
            Debug.MemoryInfo info = new Debug.MemoryInfo();
            Debug.getMemoryInfo(info);
            appMemoryMb = Math.max(0, info.getTotalPss() / 1024);
        } catch (RuntimeException e) {
            Log.w(KioskApp.LOG, "App memory status unavailable", e);
        }
        return new SystemPressure(memoryPercent, memoryUsedMb, memoryTotalMb, memoryLow, storagePercent, appMemoryMb);
    }
}
