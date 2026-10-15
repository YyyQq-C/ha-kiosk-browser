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

    public static final int MIN_LAN_PASSWORD_LENGTH = 8;
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

    /**
     * 校验全部输入。
     *
     * @param text 各文字字段的原始输入，键为本类常量
     * @param motionEnabled 人体检测开关
     * @param mqttEnabled MQTT 开关
     * @param lanEnabled 局域网管理开关
     * @param lockTaskEnabled 设备所有者锁定模式开关
     */
    public static Result parse(Map<String, String> text, boolean motionEnabled, boolean mqttEnabled,
                               boolean lanEnabled, boolean lockTaskEnabled) {
        Map<String, String> errors = new LinkedHashMap<>();
        KioskConfig.Builder b = new KioskConfig.Builder();

        // 页面与刷新
        b.homeUrl = trimmed(text, HOME_URL);
        String lower = b.homeUrl.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.startsWith("https://") || lower.startsWith("http://")) || b.homeUrl.length() < 10
                || b.homeUrl.contains(" ")) {
            errors.put(HOME_URL, "请输入以 http:// 或 https:// 开头的完整网址");
        }
        b.idleReloadHours = number(text, IDLE_RELOAD_HOURS, 0, 720, "空闲重新加载", errors);
        b.retrySeconds = number(text, RETRY_SECONDS, 0, 3600, "失败重试", errors);

        // 亮度与人体检测
        b.watchBrightness = number(text, WATCH_BRIGHTNESS, 1, 100, "观看亮度", errors);
        b.standbyBrightness = number(text, STANDBY_BRIGHTNESS, 0, 100, "待机亮度", errors);
        b.dimDelaySeconds = number(text, DIM_DELAY_SECONDS, 0, 86400, "无人后降亮度", errors);
        b.motionEnabled = motionEnabled;
        b.motionSensitivity = number(text, MOTION_SENSITIVITY, 0, 100, "动作灵敏度", errors);
        b.presenceHoldSeconds = number(text, PRESENCE_HOLD_SECONDS, 1, 3600, "有人状态保持", errors);

        // MQTT：关闭时仍保存已填内容，只在开启时要求必填项
        b.mqttEnabled = mqttEnabled;
        b.mqttHost = trimmed(text, MQTT_HOST);
        b.mqttPort = number(text, MQTT_PORT, 1, 65535, "MQTT 端口", errors);
        b.mqttUsername = trimmed(text, MQTT_USERNAME);
        b.mqttPassword = raw(text, MQTT_PASSWORD);
        b.mqttBaseTopic = trimmed(text, MQTT_BASE_TOPIC);
        b.mqttDiscoveryPrefix = trimmed(text, MQTT_DISCOVERY_PREFIX);
        b.mqttStateIntervalSeconds = number(text, MQTT_STATE_INTERVAL, 0, 86400, "上报间隔", errors);
        if (mqttEnabled && (b.mqttHost.isEmpty() || b.mqttHost.contains(" ") || b.mqttHost.contains("/"))) {
            errors.put(MQTT_HOST, "开启 MQTT 时需要填写主机名或 IP，不带协议和路径");
        }
        if (!TOPIC.matcher(b.mqttBaseTopic).matches()) {
            errors.put(MQTT_BASE_TOPIC, "主题只能包含字母、数字、下划线、短横线和中间的 /");
        }
        if (!TOPIC.matcher(b.mqttDiscoveryPrefix).matches()) {
            errors.put(MQTT_DISCOVERY_PREFIX, "发现前缀只能包含字母、数字、下划线、短横线和中间的 /");
        }

        // 局域网管理：开启时必须设置足够长的密码
        b.lanEnabled = lanEnabled;
        b.lanPort = number(text, LAN_PORT, 1024, 65535, "局域网端口", errors);
        b.lanPassword = raw(text, LAN_PASSWORD);
        if (lanEnabled && b.lanPassword.length() < MIN_LAN_PASSWORD_LENGTH) {
            errors.put(LAN_PASSWORD, "开启局域网管理时密码至少 " + MIN_LAN_PASSWORD_LENGTH + " 位");
        }

        b.lockTaskEnabled = lockTaskEnabled;
        return errors.isEmpty() ? new Result(b.build(), errors) : new Result(null, errors);
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
        return m;
    }

    /** 解析整数并检查范围，失败时记录中文提示并返回下限。 */
    private static int number(Map<String, String> text, String key, int min, int max, String label,
                              Map<String, String> errors) {
        String value = trimmed(text, key);
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) return parsed;
        } catch (NumberFormatException ignored) {
            // 非数字与越界使用同一条提示
        }
        errors.put(key, label + "请输入 " + min + " 到 " + max + " 之间的整数");
        return min;
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
