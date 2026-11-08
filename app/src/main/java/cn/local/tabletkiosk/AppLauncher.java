package cn.local.tabletkiosk;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.util.Log;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 列出、描述和打开桌面上能看到的外部应用，以及把面板切回前台。 */
final class AppLauncher {
    /** 一个可显示的应用：包名、名称和图标。 */
    static final class Entry {
        final String packageName;
        final String label;
        final Drawable icon;

        /** 保存包名、名称和图标。 */
        Entry(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    private AppLauncher() {
    }

    /** 桌面上能打开的应用，不含本应用，按名称排序。 */
    static List<Entry> installed(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentActivities(main, 0);
        } catch (RuntimeException e) {
            Log.w(KioskApp.LOG, "Failed to list launcher apps", e);
            return new ArrayList<>();
        }
        Set<String> seen = new HashSet<>();
        List<Entry> out = new ArrayList<>();
        for (ResolveInfo info : found) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (pkg == null || pkg.equals(context.getPackageName()) || !ExternalApps.validPackage(pkg)) continue;
            if (!seen.add(pkg)) continue;
            out.add(new Entry(pkg, String.valueOf(info.loadLabel(pm)), info.loadIcon(pm)));
        }
        Collator collator = Collator.getInstance(Locale.getDefault());
        out.sort((a, b) -> collator.compare(a.label, b.label));
        return out;
    }

    /** 已安装且能从桌面打开时返回名称和图标，否则返回 null。 */
    static Entry describe(Context context, String packageName) {
        if (launchIntent(context, packageName) == null) return null;
        PackageManager pm = context.getPackageManager();
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return new Entry(packageName, String.valueOf(pm.getApplicationLabel(info)), pm.getApplicationIcon(info));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** 桌面图标对应的启动意图；没有安装、没有桌面入口或包名不合法时返回 null。 */
    static Intent launchIntent(Context context, String packageName) {
        if (!ExternalApps.validPackage(packageName) || packageName.equals(context.getPackageName())) return null;
        try {
            return context.getPackageManager().getLaunchIntentForPackage(packageName);
        } catch (RuntimeException e) {
            Log.w(KioskApp.LOG, "Failed to resolve launch intent for " + packageName, e);
            return null;
        }
    }

    /** 像从桌面点图标一样打开应用；已在运行时回到它上次的画面。 */
    static boolean launch(Context context, String packageName) {
        Intent intent = launchIntent(context, packageName);
        if (intent == null) {
            Log.w(KioskApp.LOG, "External app is not installed or has no launcher entry: " + packageName);
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            context.startActivity(intent);
            Log.i(KioskApp.LOG, "Opened external app " + packageName);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(KioskApp.LOG, "Failed to open external app " + packageName, e);
            return false;
        }
    }

    /** 把面板切回前台。设备所有者或已允许悬浮窗时，系统允许从后台切回。 */
    static void showPanel(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(intent);
            Log.i(KioskApp.LOG, "Bringing dashboard to the front");
        } catch (RuntimeException e) {
            Log.w(KioskApp.LOG, "Failed to bring dashboard to the front", e);
        }
    }
}
