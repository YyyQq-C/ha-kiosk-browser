package cn.local.tabletkiosk;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;
import java.util.List;

/** 设备所有者能力：锁定模式、固定桌面、自动授权、关闭锁屏和远程重启；非设备所有者时全部跳过。 */
public final class DeviceOwnerPolicy {
    private DeviceOwnerPolicy() {
    }

    /** 本应用是否已被设为设备所有者。 */
    public static boolean isDeviceOwner(Context context) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
    }

    /** 设备管理员组件。 */
    static ComponentName admin(Context context) {
        return new ComponentName(context, KioskAdminReceiver.class);
    }

    /** 下发设备策略。pinHome 为假时取消固定桌面，让 Home 可以打开其他应用。 */
    public static void applyPolicies(Context context, boolean pinHome) {
        if (!isDeviceOwner(context)) return;
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        ComponentName admin = admin(context);
        String pkg = context.getPackageName();
        try {
            // 允许本应用进入锁定模式，并保留电源键长按菜单，方便手动关机
            dpm.setLockTaskPackages(admin, new String[]{pkg});
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS);

            if (pinHome) {
                // 固定为桌面，开机和按 Home 都回到本应用
                IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.addCategory(Intent.CATEGORY_DEFAULT);
                dpm.addPersistentPreferredActivity(admin, home, new ComponentName(context, MainActivity.class));
            } else {
                dpm.clearPackagePersistentPreferredActivities(admin, pkg);
                Log.i(KioskApp.LOG, "Cleared persistent home so other apps can open");
            }

            // 静默授予摄像头权限
            boolean granted = dpm.setPermissionGrantState(admin, pkg, Manifest.permission.CAMERA,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            if (!granted) Log.w(KioskApp.LOG, "Camera permission could not be granted by device owner");

            // 关闭锁屏；设置了密码锁屏时系统会拒绝
            if (!dpm.setKeyguardDisabled(admin, true)) {
                Log.w(KioskApp.LOG, "Keyguard was not disabled; a secure lock screen is probably set");
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.e(KioskApp.LOG, "Failed to apply device owner policies", e);
        }
    }

    /** 按配置进入或退出锁定模式，只在设备所有者下生效。 */
    public static boolean syncLockTask(Activity activity, boolean enabled) {
        if (!isDeviceOwner(activity)) return false;
        ActivityManager am = activity.getSystemService(ActivityManager.class);
        boolean locked = am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
        DevicePolicyManager dpm = activity.getSystemService(DevicePolicyManager.class);
        try {
            if (enabled && !locked && dpm.isLockTaskPermitted(activity.getPackageName())) {
                activity.startLockTask();
                return true;
            }
            if (!enabled && locked) {
                activity.stopLockTask();
                return false;
            }
        } catch (IllegalStateException | SecurityException e) {
            Log.e(KioskApp.LOG, "Failed to change lock task mode", e);
        }
        return locked;
    }

    /** 当前是否处于锁定模式。 */
    public static boolean inLockTask(Context context) {
        ActivityManager am = context.getSystemService(ActivityManager.class);
        return am != null && am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    }

    /** 熄灭屏幕。需要设备所有者，系统会锁住屏幕直到再次唤醒。 */
    public static boolean screenOff(Context context) {
        if (!isDeviceOwner(context)) {
            Log.w(KioskApp.LOG, "Screen off rejected: app is not device owner");
            return false;
        }
        try {
            Log.i(KioskApp.LOG, "Turning screen off by remote command");
            context.getSystemService(DevicePolicyManager.class).lockNow();
            return true;
        } catch (SecurityException e) {
            Log.e(KioskApp.LOG, "Screen off failed", e);
            return false;
        }
    }

    /**
     * 取消本应用的设备所有者。Android 12 常常拒绝，调用方仍应先退出锁定。
     * 成功返回 true。
     */
    @SuppressWarnings("deprecation")
    public static boolean clearDeviceOwner(Context context) {
        if (!isDeviceOwner(context)) return false;
        try {
            context.getSystemService(DevicePolicyManager.class).clearDeviceOwnerApp(context.getPackageName());
            boolean cleared = !isDeviceOwner(context);
            Log.i(KioskApp.LOG, "Device owner clear requested, cleared=" + cleared);
            return cleared;
        } catch (SecurityException e) {
            Log.w(KioskApp.LOG, "Device owner clear rejected", e);
            return false;
        }
    }

    /** 打开另一个桌面应用。没有其他桌面时返回 false。 */
    public static boolean openOtherHome(Context context) {
        PackageManager packages = context.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN);
        probe.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> homes = packages.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY);
        String mine = context.getPackageName();
        for (ResolveInfo info : homes) {
            if (info.activityInfo == null || mine.equals(info.activityInfo.packageName)) continue;
            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.addCategory(Intent.CATEGORY_HOME);
            launch.setClassName(info.activityInfo.packageName, info.activityInfo.name);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(launch);
                Log.i(KioskApp.LOG, "Opened other home: " + info.activityInfo.packageName);
                return true;
            } catch (RuntimeException e) {
                Log.w(KioskApp.LOG, "Failed to open home " + info.activityInfo.packageName, e);
            }
        }
        Log.w(KioskApp.LOG, "No other home app found");
        return false;
    }

    /** 重启平板；非设备所有者或系统拒绝时返回 false。 */
    public static boolean reboot(Context context) {
        if (!isDeviceOwner(context)) {
            Log.w(KioskApp.LOG, "Reboot rejected: app is not device owner");
            return false;
        }
        try {
            Log.i(KioskApp.LOG, "Rebooting device by remote command");
            context.getSystemService(DevicePolicyManager.class).reboot(admin(context));
            return true;
        } catch (IllegalStateException | SecurityException e) {
            Log.e(KioskApp.LOG, "Device reboot failed", e);
            return false;
        }
    }
}
