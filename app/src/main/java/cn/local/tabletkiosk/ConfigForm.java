package cn.local.tabletkiosk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** 把设置页的文字输入校验并转换成配置；不依赖 Android，便于单测。 */
public final class ConfigForm {
    public static final String HOME_URL = "home_url";
    public static final String IDLE_RELOAD_HOURS = "idle_reload_hours";
    public static final String RETRY_SECONDS = "retry_seconds";
    public static final String WATCH_BRIGHTNESS = "watch_brightness";
    public static final String STANDBY_BRIGHTNESS = "standby_brightness";
    public static final String DIM_DELAY_SECONDS = "dim_delay_seconds";
    public static final String SCREENSAVER_BRIGHTNESS = "screensaver_brightness";
    public static final String MOTION_SENSITIVITY = "motion_sensitivity";
    public static final String PRESENCE_HOLD_SECONDS = "presence_hold_seconds";
    public static final String MQTT_HOST = "mqtt_host";
    public static final String MQTT_PORT = "mqtt_port";
    public static final String MQTT_USERNAME = "mqtt_username";
    public static final String MQTT_PASSWORD = "mqtt_password";
    public static final String MQTT_BASE_TOPIC = "mqtt_base_topic";
    public static final String MQTT_DISCOVERY_PREFIX = "mqtt_discovery_prefix";
    public static final String MQTT_STATE_INTERVAL = "mqtt_state_interval";
    public static final String LAN_PORT = "lan_port";
    public static final String LAN_PASSWORD = "lan_password";
    public static final String DAY_START_HOUR = "day_start_hour";
    public static final String NIGHT_START_HOUR = "night_start_hour";
    public static final String DAY_BRIGHTNESS = "day_brightness";
    public static final String NIGHT_BRIGHTNESS = "night_brightness";
    public static final String DAILY_RELOAD_HOUR = "daily_reload_hour";
    public static final String DAILY_RELOAD_MINUTE = "daily_reload_minute";
    public static final String SLEEP_START_HOUR = "sleep_start_hour";
    public static final String SLEEP_END_HOUR = "sleep_end_hour";
    public static final String VOLUME = "volume";
    public static final String SETTINGS_PIN = "settings_pin";
    public static final String WATCHDOG_SECONDS = "watchdog_seconds";
    public static final String WAKE_SENSITIVITY = "wake_sensitivity";
    public static final String SPEECH_WAIT_SECONDS = "speech_wait_seconds";

    public static final int MIN_LAN_PASSWORD_LENGTH = 8;
    /** 校验失败时写入 errors 的稳定代码，界面按系统语言翻译。 */
    public static final String ERR_URL = "url";
    public static final String ERR_MQTT_HOST = "mqtt_host";
    public static final String ERR_TOPIC = "topic";
    public static final String ERR_PREFIX = "prefix";
    public static final String ERR_LAN_PASSWORD = "lan_password";
    public static final String ERR_PIN = "pin";
    public static final String ERR_RANGE_PREFIX = "range:";
    private static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9_\\-]+(/[A-Za-z0-9_\\-]+)*");

    private ConfigForm() {
    }

    /** 校验结果：成功时 config 非空，失败时 errors 按字段给出中文提示。 */
    public static final class Result {
        public final KioskConfig config;
        public final Map<String, String> errors;

        /** 保存校验结论。 */
        Result(KioskConfig config, Map<String, String> errors) {
            this.config = config;
            this.errors = errors;
        }

        /** 是否全部字段通过校验。 */
        public boolean ok() {
            return config != null;
        }
    }

    public static Result parse(Map<String, String> text, boolean motionEnabled, boolean mqttEnabled,
                               boolean lanEnabled, boolean lockTaskEnabled) {
        return parse(text, motionEnabled, mqttEnabled, lanEnabled, lockTaskEnabled, false);
    }

    public static Result parse(Map<String, String> text, boolean motionEnabled, boolean mqttEnabled,
                               boolean lanEnabled, boolean lockTaskEnabled, boolean scheduleEnabled) {
        return parse(text, motionEnabled, mqttEnabled, lanEnabled, lockTaskEnabled, scheduleEnabled, false, false);
    }

    /**
     * 校验全部输入。
     *
     * @param dailyReloadEnabled 每天固定时刻刷新
     * @param sleepEnabled 夜间熄屏
     */
    public static Result parse(Map<String, String> text, boolean motionEnabled, boolean mqttEnabled,
                               boolean lanEnabled, boolean lockTaskEnabled, boolean scheduleEnabled,
                               boolean dailyReloadEnabled, boolean sleepEnabled) {
        return parse(text, motionEnabled, mqttEnabled, lanEnabled, lockTaskEnabled, scheduleEnabled,
                dailyReloadEnabled, sleepEnabled, false);
    }

    /**
     * 校验全部输入。
     *
     * @param assistEnabled 语音助手常驻唤醒
     */
    public static Result parse(Map<String, String> text, boolean motionEnabled, boolean mqttEnabled,
                               boolean lanEnabled, boolean lockTaskEnabled, boolean scheduleEnabled,
                               boolean dailyReloadEnabled, boolean sleepEnabled, boolean assistEnabled) {
        return parse(text, motionEnabled, mqttEnabled, lanEnabled, lockTaskEnabled, scheduleEnabled,
                dailyReloadEnabled, sleepEnabled, assistEnabled, true, true, true);
    }

    /**
     * 校验全部输入。屏保三个开关缺省为打开，保持旧调用的现有行为。
     *
     * @param screensaverEnabled 是否盖上待机屏保
     * @param screensaverWeather 屏保是否显示天气
     * @param screensaverLunar 屏保是否显示农历
     */
    public static Result parse(Map<String, String> text, boolean motionEnabled, boolean mqttEnabled,
                               boolean lanEnabled, boolean lockTaskEnabled, boolean scheduleEnabled,
                               boolean dailyReloadEnabled, boolean sleepEnabled, boolean assistEnabled,
                               boolean screensaverEnabled, boolean screensaverWeather, boolean screensaverLunar) {
        Map<String, String> errors = new LinkedHashMap<>();
        KioskConfig.Builder b = new KioskConfig.Builder();

        // 页面与刷新
        b.homeUrl = trimmed(text, HOME_URL);
        String lower = b.homeUrl.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.startsWith("https://") || lower.startsWith("http://")) || b.homeUrl.length() < 10
                || b.homeUrl.contains(" ")) {
            errors.put(HOME_URL, ERR_URL);
        }
        b.idleReloadHours = number(text, IDLE_RELOAD_HOURS, 0, 720, errors);
        b.retrySeconds = number(text, RETRY_SECONDS, 0, 3600, errors);

        // 亮度与人体检测
        b.watchBrightness = number(text, WATCH_BRIGHTNESS, 1, 100, errors);
        b.standbyBrightness = number(text, STANDBY_BRIGHTNESS, 0, 100, errors);
        b.dimDelaySeconds = number(text, DIM_DELAY_SECONDS, 0, 86400, errors);
        b.screensaverEnabled = screensaverEnabled;
        b.screensaverWeather = screensaverWeather;
        b.screensaverLunar = screensaverLunar;
        // 旧配置没有屏保亮度时跟待机亮度走，避免升级后突然变亮或全黑
        if (blank(text, SCREENSAVER_BRIGHTNESS)) {
            b.screensaverBrightness = b.standbyBrightness;
        } else {
            b.screensaverBrightness = number(text, SCREENSAVER_BRIGHTNESS, 0, 100, errors);
        }
        b.motionEnabled = motionEnabled;
        b.motionSensitivity = number(text, MOTION_SENSITIVITY, 0, 100, errors);
        b.presenceHoldSeconds = number(text, PRESENCE_HOLD_SECONDS, 1, 3600, errors);

        // MQTT：关闭时仍保存已填内容，只在开启时要求必填项
        b.mqttEnabled = mqttEnabled;
        b.mqttHost = trimmed(text, MQTT_HOST);
        b.mqttPort = number(text, MQTT_PORT, 1, 65535, errors);
        b.mqttUsername = trimmed(text, MQTT_USERNAME);
        b.mqttPassword = raw(text, MQTT_PASSWORD);
        b.mqttBaseTopic = trimmed(text, MQTT_BASE_TOPIC);
        b.mqttDiscoveryPrefix = trimmed(text, MQTT_DISCOVERY_PREFIX);
        b.mqttStateIntervalSeconds = number(text, MQTT_STATE_INTERVAL, 0, 86400, errors);
        if (mqttEnabled && (b.mqttHost.isEmpty() || b.mqttHost.contains(" ") || b.mqttHost.contains("/"))) {
            errors.put(MQTT_HOST, ERR_MQTT_HOST);
        }
        if (!TOPIC.matcher(b.mqttBaseTopic).matches()) {
            errors.put(MQTT_BASE_TOPIC, ERR_TOPIC);
        }
        if (!TOPIC.matcher(b.mqttDiscoveryPrefix).matches()) {
            errors.put(MQTT_DISCOVERY_PREFIX, ERR_PREFIX);
        }

        // 局域网管理：开启时必须设置足够长的密码
        b.lanEnabled = lanEnabled;
        b.lanPort = number(text, LAN_PORT, 1024, 65535, errors);
        b.lanPassword = raw(text, LAN_PASSWORD);
        if (lanEnabled && b.lanPassword.length() < MIN_LAN_PASSWORD_LENGTH) {
            errors.put(LAN_PASSWORD, ERR_LAN_PASSWORD);
        }

        b.lockTaskEnabled = lockTaskEnabled;
        b.scheduleEnabled = scheduleEnabled;
        b.dayStartHour = number(text, DAY_START_HOUR, 0, 23, errors);
        b.nightStartHour = number(text, NIGHT_START_HOUR, 0, 23, errors);
        b.dayBrightness = number(text, DAY_BRIGHTNESS, 1, 100, errors);
        b.nightBrightness = number(text, NIGHT_BRIGHTNESS, 1, 100, errors);
        b.dailyReloadEnabled = dailyReloadEnabled;
        b.dailyReloadHour = number(text, DAILY_RELOAD_HOUR, 0, 23, errors);
        b.dailyReloadMinute = number(text, DAILY_RELOAD_MINUTE, 0, 59, errors);
        b.sleepEnabled = sleepEnabled;
        b.sleepStartHour = number(text, SLEEP_START_HOUR, 0, 23, errors);
        b.sleepEndHour = number(text, SLEEP_END_HOUR, 0, 23, errors);
        b.volume = number(text, VOLUME, 0, 100, errors);
        // 缺键表示这次提交不改设置密码，保持构建器默认空值，由调用方写回已保存的密码
        if (text.containsKey(SETTINGS_PIN)) {
            b.settingsPin = trimmed(text, SETTINGS_PIN);
            if (!b.settingsPin.isEmpty() && !b.settingsPin.matches("\\d{4,8}")) {
                errors.put(SETTINGS_PIN, ERR_PIN);
            }
        }
        b.watchdogSeconds = number(text, WATCHDOG_SECONDS, 0, 3600, errors);
        b.assistEnabled = assistEnabled;
        if (!text.containsKey(WAKE_SENSITIVITY)) text.put(WAKE_SENSITIVITY, "50");
        b.wakeSensitivity = number(text, WAKE_SENSITIVITY, 0, 100, errors);
        // 兼容旧设置文件：没有新增字段时使用默认 5 秒。
        if (!text.containsKey(SPEECH_WAIT_SECONDS)) text.put(SPEECH_WAIT_SECONDS, "5");
        b.speechWaitSeconds = number(text, SPEECH_WAIT_SECONDS, 1, 120, errors);
        return errors.isEmpty() ? new Result(b.build(), errors) : new Result(null, errors);
    }

    /**
     * 普通保存没有提交设置密码时，沿用已保存的值。
     * 缺键解析出来的空字符串不代表用户取消了密码。
     */
    public static KioskConfig preserveSettingsPin(KioskConfig parsed, String savedPin) {
        if (parsed == null) return null;
        String pin = savedPin == null ? "" : savedPin;
        if (pin.equals(parsed.settingsPin)) return parsed;
        KioskConfig.Builder builder = parsed.toBuilder();
        builder.settingsPin = pin;
        return builder.build();
    }

    /** 把配置转回设置页需要显示的文字。 */
    public static Map<String, String> toText(KioskConfig c) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(HOME_URL, c.homeUrl);
        m.put(IDLE_RELOAD_HOURS, Integer.toString(c.idleReloadHours));
        m.put(RETRY_SECONDS, Integer.toString(c.retrySeconds));
        m.put(WATCH_BRIGHTNESS, Integer.toString(c.watchBrightness));
        m.put(STANDBY_BRIGHTNESS, Integer.toString(c.standbyBrightness));
        m.put(DIM_DELAY_SECONDS, Integer.toString(c.dimDelaySeconds));
        m.put(SCREENSAVER_BRIGHTNESS, Integer.toString(c.screensaverBrightness));
        m.put(MOTION_SENSITIVITY, Integer.toString(c.motionSensitivity));
        m.put(PRESENCE_HOLD_SECONDS, Integer.toString(c.presenceHoldSeconds));
        m.put(MQTT_HOST, c.mqttHost);
        m.put(MQTT_PORT, Integer.toString(c.mqttPort));
        m.put(MQTT_USERNAME, c.mqttUsername);
        m.put(MQTT_PASSWORD, c.mqttPassword);
        m.put(MQTT_BASE_TOPIC, c.mqttBaseTopic);
        m.put(MQTT_DISCOVERY_PREFIX, c.mqttDiscoveryPrefix);
        m.put(MQTT_STATE_INTERVAL, Integer.toString(c.mqttStateIntervalSeconds));
        m.put(LAN_PORT, Integer.toString(c.lanPort));
        m.put(LAN_PASSWORD, c.lanPassword);
        m.put(DAY_START_HOUR, Integer.toString(c.dayStartHour));
        m.put(NIGHT_START_HOUR, Integer.toString(c.nightStartHour));
        m.put(DAY_BRIGHTNESS, Integer.toString(c.dayBrightness));
        m.put(NIGHT_BRIGHTNESS, Integer.toString(c.nightBrightness));
        m.put(DAILY_RELOAD_HOUR, Integer.toString(c.dailyReloadHour));
        m.put(DAILY_RELOAD_MINUTE, Integer.toString(c.dailyReloadMinute));
        m.put(SLEEP_START_HOUR, Integer.toString(c.sleepStartHour));
        m.put(SLEEP_END_HOUR, Integer.toString(c.sleepEndHour));
        m.put(VOLUME, Integer.toString(c.volume));
        m.put(SETTINGS_PIN, c.settingsPin);
        m.put(WATCHDOG_SECONDS, Integer.toString(c.watchdogSeconds));
        m.put(WAKE_SENSITIVITY, Integer.toString(c.wakeSensitivity));
        m.put(SPEECH_WAIT_SECONDS, Integer.toString(c.speechWaitSeconds));
        return m;
    }

    /** 解析整数并检查范围，失败时记录 range:下限:上限 并返回下限。 */
    private static int number(Map<String, String> text, String key, int min, int max, Map<String, String> errors) {
        String value = trimmed(text, key);
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) return parsed;
        } catch (NumberFormatException ignored) {
            // 非数字与越界使用同一条提示
        }
        errors.put(key, ERR_RANGE_PREFIX + min + ":" + max);
        return min;
    }

    /** 键不存在或只有空白时视为缺省，供旧配置回落默认。 */
    private static boolean blank(Map<String, String> text, String key) {
        return !text.containsKey(key) || raw(text, key).trim().isEmpty();
    }

    /** 读取并去掉首尾空格。 */
    private static String trimmed(Map<String, String> text, String key) {
        return raw(text, key).trim();
    }

    /** 读取原文，密码不去空格。 */
    private static String raw(Map<String, String> text, String key) {
        String value = text.get(key);
        return value == null ? "" : value;
    }
}
