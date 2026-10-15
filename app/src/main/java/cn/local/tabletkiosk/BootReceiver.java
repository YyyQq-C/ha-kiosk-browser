package cn.local.tabletkiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/** 开机或应用更新后启动前台服务，并在系统允许时打开主界面。 */
public final class BootReceiver extends BroadcastReceiver {
    /** 处理开机完成和本应用更新广播。 */
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        Log.i(KioskApp.LOG, "Received " + action);
        try {
            context.startForegroundService(new Intent(context, KioskService.class));
        } catch (IllegalStateException e) {
            Log.e(KioskApp.LOG, "Failed to start kiosk service after " + action, e);
        }

        // 后台启动界面受系统限制：设备所有者或已授予悬浮窗权限时才允许
        boolean owner = DeviceOwnerPolicy.isDeviceOwner(context);
        if (!owner && !Settings.canDrawOverlays(context)) {
            Log.w(KioskApp.LOG, "Cannot open dashboard after boot: no device owner or overlay permission");
            return;
        }
        try {
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.e(KioskApp.LOG, "Failed to open dashboard after " + action, e);
        }
    }
}
