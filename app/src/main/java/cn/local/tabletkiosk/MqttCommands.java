package cn.local.tabletkiosk;

/** 解析收到的 MQTT 消息；不依赖 Android，便于单测。 */
final class MqttCommands {
    /** 命令类型。 */
    enum Type { NONE, RELOAD, WAKE, REBOOT, BRIGHTNESS, HA_ONLINE }

    /** 一条解析后的命令；亮度命令带数值。 */
    static final class Command {
        final Type type;
        final int value;

        /** 保存命令类型和数值。 */
        Command(Type type, int value) {
            this.type = type;
            this.value = value;
        }
    }

    private static final Command NONE = new Command(Type.NONE, 0);

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
            return "online".equalsIgnoreCase(body) ? new Command(Type.HA_ONLINE, 0) : NONE;
        }
        if (topic.equals(topics.command(MqttTopics.CMD_RELOAD))) return new Command(Type.RELOAD, 0);
        if (topic.equals(topics.command(MqttTopics.CMD_WAKE))) return new Command(Type.WAKE, 0);
        if (topic.equals(topics.command(MqttTopics.CMD_REBOOT))) return new Command(Type.REBOOT, 0);
        if (topic.equals(topics.command(MqttTopics.CMD_BRIGHTNESS))) {
            Integer value = parseBrightness(body);
            return value == null ? NONE : new Command(Type.BRIGHTNESS, value);
        }
        return NONE;
    }

    /** 解析亮度百分比：接受 0 到 100 的整数或小数，0 按 1 处理；其他内容返回 null。 */
    static Integer parseBrightness(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty() || t.length() > 16) return null;
        try {
            double d = Double.parseDouble(t);
            if (Double.isNaN(d) || d < 0 || d > 100) return null;
            return Math.max(1, (int) Math.round(d));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
