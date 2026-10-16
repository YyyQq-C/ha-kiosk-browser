package cn.local.tabletkiosk;

/** 解析收到的 MQTT 消息；不依赖 Android，便于单测。 */
final class MqttCommands {
    /** 命令类型。 */
    enum Type {
        NONE, RELOAD, WAKE, REBOOT, BRIGHTNESS, HA_ONLINE, URL, HOME, MOTION, STANDBY_BRIGHTNESS,
        DIM_DELAY, LOCK, SCREEN_OFF, SCREENSHOT, NOTIFY, TTS, SCHEDULE, DAY_BRIGHTNESS, NIGHT_BRIGHTNESS,
        VOLUME, PLAY, DAILY, DAILY_HOUR, DAILY_MINUTE, SLEEP, SLEEP_START, SLEEP_END, WATCHDOG
    }

    /** 一条解析后的命令。开关用 value 1 或 0，文本命令带 text。 */
    static final class Command {
        final Type type;
        final int value;
        final String text;

        /** 保存命令类型、数值和文本。 */
        Command(Type type, int value, String text) {
            this.type = type;
            this.value = value;
            this.text = text == null ? "" : text;
        }
    }

    private static final Command NONE = new Command(Type.NONE, 0, "");
    private static final int MAX_TEXT = 200;
    private static final int MAX_URL = 2000;

    private MqttCommands() {
    }

    /**
     * 按主题和内容解析命令。
     *
     * @param topics 本设备主题
     * @param topic 收到消息的主题
     * @param payload 消息文本
     */
    static Command parse(MqttTopics topics, String topic, String payload) {
        if (topic == null) return NONE;
        String body = payload == null ? "" : payload.trim();
        if (topic.equals(topics.haStatus())) {
            return "online".equalsIgnoreCase(body) ? command(Type.HA_ONLINE) : NONE;
        }
        if (topic.equals(topics.command(MqttTopics.CMD_RELOAD))) return command(Type.RELOAD);
        if (topic.equals(topics.command(MqttTopics.CMD_WAKE))) return command(Type.WAKE);
        if (topic.equals(topics.command(MqttTopics.CMD_REBOOT))) return command(Type.REBOOT);
        if (topic.equals(topics.command(MqttTopics.CMD_HOME))) return command(Type.HOME);
        if (topic.equals(topics.command(MqttTopics.CMD_SCREEN_OFF))) return command(Type.SCREEN_OFF);
        if (topic.equals(topics.command(MqttTopics.CMD_SCREENSHOT))) return command(Type.SCREENSHOT);
        if (topic.equals(topics.command(MqttTopics.CMD_BRIGHTNESS))) return percent(Type.BRIGHTNESS, body, 1);
        if (topic.equals(topics.command(MqttTopics.CMD_STANDBY))) return percent(Type.STANDBY_BRIGHTNESS, body, 0);
        if (topic.equals(topics.command(MqttTopics.CMD_DAY))) return percent(Type.DAY_BRIGHTNESS, body, 1);
        if (topic.equals(topics.command(MqttTopics.CMD_NIGHT))) return percent(Type.NIGHT_BRIGHTNESS, body, 1);
        if (topic.equals(topics.command(MqttTopics.CMD_DIM_DELAY))) return range(Type.DIM_DELAY, body, 0, 86400);
        if (topic.equals(topics.command(MqttTopics.CMD_MOTION))) return onOff(Type.MOTION, body);
        if (topic.equals(topics.command(MqttTopics.CMD_LOCK))) return onOff(Type.LOCK, body);
        if (topic.equals(topics.command(MqttTopics.CMD_SCHEDULE))) return onOff(Type.SCHEDULE, body);
        if (topic.equals(topics.command(MqttTopics.CMD_URL))) return http(Type.URL, body);
        if (topic.equals(topics.command(MqttTopics.CMD_NOTIFY))) return text(Type.NOTIFY, body);
        if (topic.equals(topics.command(MqttTopics.CMD_TTS))) return text(Type.TTS, body);
        if (topic.equals(topics.command(MqttTopics.CMD_VOLUME))) return percent(Type.VOLUME, body, 0);
        if (topic.equals(topics.command(MqttTopics.CMD_PLAY))) return http(Type.PLAY, body);
        if (topic.equals(topics.command(MqttTopics.CMD_DAILY))) return onOff(Type.DAILY, body);
        if (topic.equals(topics.command(MqttTopics.CMD_DAILY_HOUR))) return range(Type.DAILY_HOUR, body, 0, 23);
        if (topic.equals(topics.command(MqttTopics.CMD_DAILY_MINUTE))) return range(Type.DAILY_MINUTE, body, 0, 59);
        if (topic.equals(topics.command(MqttTopics.CMD_SLEEP))) return onOff(Type.SLEEP, body);
        if (topic.equals(topics.command(MqttTopics.CMD_SLEEP_START))) return range(Type.SLEEP_START, body, 0, 23);
        if (topic.equals(topics.command(MqttTopics.CMD_SLEEP_END))) return range(Type.SLEEP_END, body, 0, 23);
        if (topic.equals(topics.command(MqttTopics.CMD_WATCHDOG))) return range(Type.WATCHDOG, body, 0, 3600);
        return NONE;
    }

    /** 解析亮度百分比：接受 0 到 100，0 按 1 处理，供观看亮度使用。 */
    static Integer parseBrightness(String text) {
        Integer value = parseRange(text, 0, 100, 16);
        return value == null ? null : Math.max(1, value);
    }

    /** 不带附加内容的命令。 */
    private static Command command(Type type) {
        return new Command(type, 0, "");
    }

    /** 亮度类数值；无法解析时丢弃。 */
    private static Command percent(Type type, String body, int floor) {
        Integer value = parseRange(body, 0, 100, 16);
        if (value == null) return NONE;
        return new Command(type, Math.max(floor, value), "");
    }

    /** 整数范围；无法解析时丢弃。 */
    private static Command range(Type type, String body, int min, int max) {
        Integer value = parseRange(body, min, max, 8);
        return value == null ? NONE : new Command(type, value, "");
    }

    /** ON/OFF 开关。 */
    private static Command onOff(Type type, String body) {
        Boolean on = parseSwitch(body);
        return on == null ? NONE : new Command(type, on ? 1 : 0, "");
    }

    /** 只接受 http 或 https 网址。 */
    private static Command http(Type type, String body) {
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        if (body.length() < 10 || body.length() > MAX_URL || body.contains(" ")
                || !(lower.startsWith("https://") || lower.startsWith("http://"))) {
            return NONE;
        }
        return new Command(type, 0, body);
    }

    /** 通知或语音文本，空内容和过长内容丢弃。 */
    private static Command text(Type type, String body) {
        if (body.isEmpty() || body.length() > MAX_TEXT) return NONE;
        return new Command(type, 0, body);
    }

    /** 解析 ON、OFF、true、false、1、0。 */
    static Boolean parseSwitch(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.equalsIgnoreCase("on") || t.equalsIgnoreCase("true") || t.equals("1")) return true;
        if (t.equalsIgnoreCase("off") || t.equalsIgnoreCase("false") || t.equals("0")) return false;
        return null;
    }

    /** 解析闭区间整数，拒绝小数以外的非数字。 */
    private static Integer parseRange(String text, int min, int max, int maxLength) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty() || t.length() > maxLength) return null;
        try {
            double d = Double.parseDouble(t);
            if (Double.isNaN(d) || d < min || d > max) return null;
            return (int) Math.round(d);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
