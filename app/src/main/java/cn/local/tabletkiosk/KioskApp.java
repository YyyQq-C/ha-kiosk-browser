package cn.local.tabletkiosk;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

/** 应用入口：初始化状态中心和前台服务通知渠道。 */
public final class KioskApp extends Application {
    public static final String LOG = "TabletKiosk";
    public static final String CHANNEL = "kiosk_service";

    /** 创建通知渠道并加载配置。 */
    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "后台连接", NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("保持 MQTT 和局域网管理接口在线");
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        KioskHub.init(this);
    }
}
