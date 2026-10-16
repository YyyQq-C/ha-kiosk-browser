package cn.local.tabletkiosk;

/** 本设备使用的全部 MQTT 主题。 */
final class MqttTopics {
    static final String CMD_RELOAD = "reload";
    static final String CMD_WAKE = "wake";
    static final String CMD_REBOOT = "reboot";
    static final String CMD_BRIGHTNESS = "brightness";
    static final String CMD_URL = "url";
    static final String CMD_HOME = "home";
    static final String CMD_MOTION = "motion";
    static final String CMD_STANDBY = "standby_brightness";
    static final String CMD_DIM_DELAY = "dim_delay";
    static final String CMD_LOCK = "lock_task";
    static final String CMD_SCREEN_OFF = "screen_off";
    static final String CMD_SCREENSHOT = "screenshot";
    static final String CMD_NOTIFY = "notify";
    static final String CMD_TTS = "tts";
    static final String CMD_SCHEDULE = "schedule";
    static final String CMD_DAY = "day_brightness";
    static final String CMD_NIGHT = "night_brightness";
    static final String CMD_VOLUME = "volume";
    static final String CMD_PLAY = "play";
    static final String CMD_DAILY = "daily_reload";
    static final String CMD_DAILY_HOUR = "daily_hour";
    static final String CMD_DAILY_MINUTE = "daily_minute";
    static final String CMD_SLEEP = "sleep";
    static final String CMD_SLEEP_START = "sleep_start";
    static final String CMD_SLEEP_END = "sleep_end";
    static final String CMD_WATCHDOG = "watchdog";

    final String deviceId;
    private final String base;
    private final String discoveryPrefix;

    /**
     * @param baseTopic 用户设置的基础主题
     * @param discoveryPrefix HA 自动发现前缀
     * @param deviceId 本机设备编号
     */
    MqttTopics(String baseTopic, String discoveryPrefix, String deviceId) {
        this.base = baseTopic + "/" + deviceId;
        this.discoveryPrefix = discoveryPrefix;
        this.deviceId = deviceId;
    }

    /** 在线状态主题，断线遗嘱也发到这里。 */
    String availability() {
        return base + "/availability";
    }

    /** 状态 JSON 主题。 */
    String state() {
        return base + "/state";
    }

    /** 截屏图片主题，内容是 JPEG 字节。 */
    String camera() {
        return base + "/camera/image";
    }

    /** 某个命令的主题。 */
    String command(String name) {
        return base + "/cmd/" + name;
    }

    /** 订阅全部命令用的通配主题。 */
    String commandFilter() {
        return base + "/cmd/#";
    }

    /** Home Assistant 上线通知主题，收到 online 时重新发布注册信息。 */
    String haStatus() {
        return discoveryPrefix + "/status";
    }

    /** 某个实体的自动发现配置主题。 */
    String discovery(String component, String objectId) {
        return discoveryPrefix + "/" + component + "/" + deviceId + "/" + objectId + "/config";
    }
}
