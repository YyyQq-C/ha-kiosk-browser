package cn.local.tabletkiosk;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 设备管理员接收器；通过 adb 设为设备所有者后启用锁定相关能力。 */
public final class KioskAdminReceiver extends DeviceAdminReceiver {
    /** 管理员启用时记录日志，策略由主界面启动时统一下发。 */
    @Override
    public void onEnabled(Context context, Intent intent) {
        Log.i(KioskApp.LOG, "Device admin enabled");
    }

    /** 管理员停用时记录日志。 */
    @Override
    public void onDisabled(Context context, Intent intent) {
        Log.w(KioskApp.LOG, "Device admin disabled");
    }
}
