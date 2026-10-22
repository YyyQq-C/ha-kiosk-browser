package cn.local.tabletkiosk;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;
import java.util.Locale;

/** 在应用私有 SharedPreferences 中读写配置；密码只保存在本机，不写入日志。 */
public final class KioskConfigStore {
    private static final String PREFS = "kiosk_config";
    private static final String DEVICE_ID = "device_id";
    private static final String BATTERY_PROMPTED = "battery_prompted";
    private static final String HA_TOKEN = "ha_token";
    private static final String SETUP_DONE = "setup_done";
    private static final String UPDATE_CHECKED = "update_checked_at";
    private final SharedPreferences prefs;

    /** 绑定应用私有配置文件。 */
    public KioskConfigStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 读取配置，缺失项使用默认值。 */
    public KioskConfig load() {
        KioskConfig d = KioskConfig.defaults();
        KioskConfig.Builder b = d.toBuilder();
        b.homeUrl = prefs.getString(ConfigForm.HOME_URL, d.homeUrl);
        b.idleReloadHours = prefs.getInt(ConfigForm.IDLE_RELOAD_HOURS, d.idleReloadHours);
        b.retrySeconds = prefs.getInt(ConfigForm.RETRY_SECONDS, d.retrySeconds);
        b.watchBrightness = prefs.getInt(ConfigForm.WATCH_BRIGHTNESS, d.watchBrightness);
        b.standbyBrightness = prefs.getInt(ConfigForm.STANDBY_BRIGHTNESS, d.standbyBrightness);
        b.dimDelaySeconds = prefs.getInt(ConfigForm.DIM_DELAY_SECONDS, d.dimDelaySeconds);
        b.screensaverEnabled = prefs.getBoolean("screensaver_enabled", d.screensaverEnabled);
        b.screensaverWeather = prefs.getBoolean("screensaver_weather", d.screensaverWeather);
        b.screensaverLunar = prefs.getBoolean("screensaver_lunar", d.screensaverLunar);
        // 旧配置没有屏保亮度时回落到已保存的待机亮度，而不是写死默认 20
        b.screensaverBrightness = prefs.contains(ConfigForm.SCREENSAVER_BRIGHTNESS)
                ? prefs.getInt(ConfigForm.SCREENSAVER_BRIGHTNESS, b.standbyBrightness)
                : b.standbyBrightness;
        b.motionEnabled = prefs.getBoolean("motion_enabled", d.motionEnabled);
        b.motionSensitivity = prefs.getInt(ConfigForm.MOTION_SENSITIVITY, d.motionSensitivity);
        b.presenceHoldSeconds = prefs.getInt(ConfigForm.PRESENCE_HOLD_SECONDS, d.presenceHoldSeconds);
        b.mqttEnabled = prefs.getBoolean("mqtt_enabled", d.mqttEnabled);
        b.mqttHost = prefs.getString(ConfigForm.MQTT_HOST, d.mqttHost);
        b.mqttPort = prefs.getInt(ConfigForm.MQTT_PORT, d.mqttPort);
        b.mqttUsername = prefs.getString(ConfigForm.MQTT_USERNAME, d.mqttUsername);
        b.mqttPassword = prefs.getString(ConfigForm.MQTT_PASSWORD, d.mqttPassword);
        b.mqttBaseTopic = prefs.getString(ConfigForm.MQTT_BASE_TOPIC, d.mqttBaseTopic);
        b.mqttDiscoveryPrefix = prefs.getString(ConfigForm.MQTT_DISCOVERY_PREFIX, d.mqttDiscoveryPrefix);
        b.mqttStateIntervalSeconds = prefs.getInt(ConfigForm.MQTT_STATE_INTERVAL, d.mqttStateIntervalSeconds);
        b.lanEnabled = prefs.getBoolean("lan_enabled", d.lanEnabled);
        b.lanPort = prefs.getInt(ConfigForm.LAN_PORT, d.lanPort);
        b.lanPassword = prefs.getString(ConfigForm.LAN_PASSWORD, d.lanPassword);
        b.lockTaskEnabled = prefs.getBoolean("lock_task_enabled", d.lockTaskEnabled);
        b.scheduleEnabled = prefs.getBoolean("schedule_enabled", d.scheduleEnabled);
        b.dayStartHour = prefs.getInt(ConfigForm.DAY_START_HOUR, d.dayStartHour);
        b.nightStartHour = prefs.getInt(ConfigForm.NIGHT_START_HOUR, d.nightStartHour);
        b.dayBrightness = prefs.getInt(ConfigForm.DAY_BRIGHTNESS, d.dayBrightness);
        b.nightBrightness = prefs.getInt(ConfigForm.NIGHT_BRIGHTNESS, d.nightBrightness);
        b.dailyReloadEnabled = prefs.getBoolean("daily_reload_enabled", d.dailyReloadEnabled);
        b.dailyReloadHour = prefs.getInt(ConfigForm.DAILY_RELOAD_HOUR, d.dailyReloadHour);
        b.dailyReloadMinute = prefs.getInt(ConfigForm.DAILY_RELOAD_MINUTE, d.dailyReloadMinute);
        b.sleepEnabled = prefs.getBoolean("sleep_enabled", d.sleepEnabled);
        b.sleepStartHour = prefs.getInt(ConfigForm.SLEEP_START_HOUR, d.sleepStartHour);
        b.sleepEndHour = prefs.getInt(ConfigForm.SLEEP_END_HOUR, d.sleepEndHour);
        b.volume = prefs.getInt(ConfigForm.VOLUME, d.volume);
        b.settingsPin = prefs.getString(ConfigForm.SETTINGS_PIN, d.settingsPin);
        b.watchdogSeconds = prefs.getInt(ConfigForm.WATCHDOG_SECONDS, d.watchdogSeconds);
        b.assistEnabled = prefs.getBoolean("assist_enabled", d.assistEnabled);
        b.wakeSensitivity = prefs.getInt(ConfigForm.WAKE_SENSITIVITY, d.wakeSensitivity);
        b.speechWaitSeconds = prefs.getInt(ConfigForm.SPEECH_WAIT_SECONDS, d.speechWaitSeconds);
        return b.build();
    }

    /** 同步写入全部配置，保证随后读取到的是新值。 */
    public void save(KioskConfig c) {
        boolean ok = prefs.edit()
                .putString(ConfigForm.HOME_URL, c.homeUrl)
                .putInt(ConfigForm.IDLE_RELOAD_HOURS, c.idleReloadHours)
                .putInt(ConfigForm.RETRY_SECONDS, c.retrySeconds)
                .putInt(ConfigForm.WATCH_BRIGHTNESS, c.watchBrightness)
                .putInt(ConfigForm.STANDBY_BRIGHTNESS, c.standbyBrightness)
                .putInt(ConfigForm.DIM_DELAY_SECONDS, c.dimDelaySeconds)
                .putBoolean("screensaver_enabled", c.screensaverEnabled)
                .putBoolean("screensaver_weather", c.screensaverWeather)
                .putBoolean("screensaver_lunar", c.screensaverLunar)
                .putInt(ConfigForm.SCREENSAVER_BRIGHTNESS, c.screensaverBrightness)
                .putBoolean("motion_enabled", c.motionEnabled)
                .putInt(ConfigForm.MOTION_SENSITIVITY, c.motionSensitivity)
                .putInt(ConfigForm.PRESENCE_HOLD_SECONDS, c.presenceHoldSeconds)
                .putBoolean("mqtt_enabled", c.mqttEnabled)
                .putString(ConfigForm.MQTT_HOST, c.mqttHost)
                .putInt(ConfigForm.MQTT_PORT, c.mqttPort)
                .putString(ConfigForm.MQTT_USERNAME, c.mqttUsername)
                .putString(ConfigForm.MQTT_PASSWORD, c.mqttPassword)
                .putString(ConfigForm.MQTT_BASE_TOPIC, c.mqttBaseTopic)
                .putString(ConfigForm.MQTT_DISCOVERY_PREFIX, c.mqttDiscoveryPrefix)
                .putInt(ConfigForm.MQTT_STATE_INTERVAL, c.mqttStateIntervalSeconds)
                .putBoolean("lan_enabled", c.lanEnabled)
                .putInt(ConfigForm.LAN_PORT, c.lanPort)
                .putString(ConfigForm.LAN_PASSWORD, c.lanPassword)
                .putBoolean("lock_task_enabled", c.lockTaskEnabled)
                .putBoolean("schedule_enabled", c.scheduleEnabled)
                .putInt(ConfigForm.DAY_START_HOUR, c.dayStartHour)
                .putInt(ConfigForm.NIGHT_START_HOUR, c.nightStartHour)
                .putInt(ConfigForm.DAY_BRIGHTNESS, c.dayBrightness)
                .putInt(ConfigForm.NIGHT_BRIGHTNESS, c.nightBrightness)
                .putBoolean("daily_reload_enabled", c.dailyReloadEnabled)
                .putInt(ConfigForm.DAILY_RELOAD_HOUR, c.dailyReloadHour)
                .putInt(ConfigForm.DAILY_RELOAD_MINUTE, c.dailyReloadMinute)
                .putBoolean("sleep_enabled", c.sleepEnabled)
                .putInt(ConfigForm.SLEEP_START_HOUR, c.sleepStartHour)
                .putInt(ConfigForm.SLEEP_END_HOUR, c.sleepEndHour)
                .putInt(ConfigForm.VOLUME, c.volume)
                .putString(ConfigForm.SETTINGS_PIN, c.settingsPin)
                .putInt(ConfigForm.WATCHDOG_SECONDS, c.watchdogSeconds)
                .putBoolean("assist_enabled", c.assistEnabled)
                .putInt(ConfigForm.WAKE_SENSITIVITY, c.wakeSensitivity)
                .putInt(ConfigForm.SPEECH_WAIT_SECONDS, c.speechWaitSeconds)
                .commit();
        if (!ok) android.util.Log.e(KioskApp.LOG, "Failed to persist kiosk config");
    }

    /** 返回本机固定的设备编号，首次调用时随机生成，用于 MQTT 主题和实体唯一 ID。 */
    public synchronized String deviceId() {
        String id = prefs.getString(DEVICE_ID, null);
        if (id != null) return id;
        byte[] bytes = new byte[4];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder("kiosk_");
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        id = sb.toString();
        prefs.edit().putString(DEVICE_ID, id).apply();
        return id;
    }

    /** 读取用于列出仪表盘的长期访问令牌。 */
    public String haToken() {
        String token = prefs.getString(HA_TOKEN, "");
        return token == null ? "" : token;
    }

    /** 保存长期访问令牌。空字符串表示清除。 */
    public void saveHaToken(String token) {
        prefs.edit().putString(HA_TOKEN, token == null ? "" : token.trim()).commit();
    }

    /** 读取语音图标的横向位置比例，默认靠右。 */
    public float assistButtonX() {
        return FloatingPosition.fraction(prefs.getFloat("assist_button_x", 1f));
    }

    /** 读取语音图标的纵向位置比例，默认靠下。 */
    public float assistButtonY() {
        return FloatingPosition.fraction(prefs.getFloat("assist_button_y", 1f));
    }

    /** 松手时保存图标位置，不触发其他配置重载或 MQTT 重连。 */
    public void saveAssistButtonPosition(float x, float y) {
        boolean ok = prefs.edit().putFloat("assist_button_x", FloatingPosition.fraction(x))
                .putFloat("assist_button_y", FloatingPosition.fraction(y)).commit();
        if (!ok) android.util.Log.e(KioskApp.LOG, "Failed to persist assist button position");
    }

    /** 还没有确认过首页时先进入设置。已经改成自己的地址则直接跳过，仍是占位地址则再确认一次。 */
    public boolean needsSetup() {
        if (prefs.getBoolean(SETUP_DONE, false)) return false;
        String saved = prefs.getString(ConfigForm.HOME_URL, "");
        if (saved != null && !saved.isEmpty() && !KioskConfig.DEFAULT_HOME_URL.equals(saved)) {
            prefs.edit().putBoolean(SETUP_DONE, true).apply();
            return false;
        }
        return true;
    }

    /** 用户确认首页后记下，以后启动直接打开该地址。 */
    public void markSetupDone() {
        boolean ok = prefs.edit().putBoolean(SETUP_DONE, true).commit();
        if (!ok) android.util.Log.e(KioskApp.LOG, "Failed to persist setup completion");
    }

    /** 电池优化提示是否已弹出过，只在首次运行主动提示一次。 */
    public boolean batteryPrompted() {
        return prefs.getBoolean(BATTERY_PROMPTED, false);
    }

    /** 记录电池优化提示已弹出。 */
    public void markBatteryPrompted() {
        prefs.edit().putBoolean(BATTERY_PROMPTED, true).apply();
    }

    /** 上次自动检查更新的时间，没有检查过返回 0。 */
    public long updateCheckedAt() {
        return prefs.getLong(UPDATE_CHECKED, 0L);
    }

    /** 记下这次自动检查，避免每次打开都访问 GitHub。 */
    public void markUpdateChecked(long now) {
        prefs.edit().putLong(UPDATE_CHECKED, now).apply();
    }
}
