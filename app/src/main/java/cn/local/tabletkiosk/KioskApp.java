package cn.local.tabletkiosk;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

/** 应用入口：初始化状态中心和前台服务通知渠道。 */
public final class KioskApp extends Application {
    public static final String LOG = "TabletKiosk";
    public static final String CHANNEL = "kiosk_service";

    /** 按已保存的界面语言创建应用。 */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLocales.wrap(base));
    }

    /** 创建通知渠道并加载配置。 */
    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_MIN);
        channel.setDescription(getString(R.string.channel_description));
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        KioskHub.init(this);
    }
}
