package cn.local.tabletkiosk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** MQTT 主题、命令解析、状态 JSON 和自动发现配置。 */
public class MqttTest {
    private final MqttTopics topics = new MqttTopics("home_kiosk", "homeassistant", "kiosk_ab12cd34");
    private final MqttDiscovery.Device device =
            new MqttDiscovery.Device("kiosk_ab12cd34", "居家屏幕", "LENOVO", "TB128FU", "1.0.0");
    private final MqttDiscovery.Labels labels = new MqttDiscovery.Labels(
            "亮屏", "有人靠近", "电量", "充电中", "充电状态", "当前亮度", "设置亮度", "当前网址", "立即刷新", "唤醒屏幕", "重启平板",
            "当前待机", "人体检测", "待机亮度", "无人后降亮度", "锁定模式", "熄屏", "回到首页", "打开页面", "屏幕截图", "画面通知", "语音播报",
            "按时间切换亮度", "白天亮度", "夜间亮度", "本机地址", "应用版本", "设备型号", "系统版本",
            "媒体音量", "播放音频", "每天刷新", "每天刷新的小时", "每天刷新的分钟", "夜间熄屏", "熄屏开始", "熄屏结束",
            "页面看门狗", "Wi-Fi 名称", "Wi-Fi 信号",
            "CPU", "内存", "内存不足", "存储", "应用内存", "电池温度");

    /** 主题格式。 */
    @Test
    public void topicLayout() {
        assertEquals("home_kiosk/kiosk_ab12cd34/availability", topics.availability());
        assertEquals("home_kiosk/kiosk_ab12cd34/state", topics.state());
        assertEquals("home_kiosk/kiosk_ab12cd34/cmd/reload", topics.command(MqttTopics.CMD_RELOAD));
        assertEquals("home_kiosk/kiosk_ab12cd34/cmd/#", topics.commandFilter());
        assertEquals("homeassistant/status", topics.haStatus());
        assertEquals("homeassistant/sensor/kiosk_ab12cd34/battery/config", topics.discovery("sensor", "battery"));
    }

    /** 命令主题解析。 */
    @Test
    public void parsesCommands() {
        assertEquals(MqttCommands.Type.RELOAD,
                MqttCommands.parse(topics, topics.command(MqttTopics.CMD_RELOAD), "PRESS").type);
        assertEquals(MqttCommands.Type.WAKE,
                MqttCommands.parse(topics, topics.command(MqttTopics.CMD_WAKE), "PRESS").type);
        assertEquals(MqttCommands.Type.REBOOT,
                MqttCommands.parse(topics, topics.command(MqttTopics.CMD_REBOOT), "PRESS").type);
        MqttCommands.Command b = MqttCommands.parse(topics, topics.command(MqttTopics.CMD_BRIGHTNESS), " 45 ");
        assertEquals(MqttCommands.Type.BRIGHTNESS, b.type);
        assertEquals(45, b.value);
        assertEquals(MqttCommands.Type.HA_ONLINE, MqttCommands.parse(topics, "homeassistant/status", "online").type);
        assertEquals(MqttCommands.Type.NONE, MqttCommands.parse(topics, "homeassistant/status", "offline").type);
        assertEquals(MqttCommands.Type.NONE, MqttCommands.parse(topics, "other/topic", "PRESS").type);
        assertEquals(MqttCommands.Type.NONE,
                MqttCommands.parse(topics, topics.command(MqttTopics.CMD_BRIGHTNESS), "abc").type);
    }

    /** 亮度范围：0 按 1 处理，越界和非数字拒绝。 */
    @Test
    public void brightnessParsing() {
        assertEquals(Integer.valueOf(1), MqttCommands.parseBrightness("0"));
        assertEquals(Integer.valueOf(100), MqttCommands.parseBrightness("100"));
        assertEquals(Integer.valueOf(51), MqttCommands.parseBrightness("50.6"));
        assertNull(MqttCommands.parseBrightness("101"));
        assertNull(MqttCommands.parseBrightness("-1"));
        assertNull(MqttCommands.parseBrightness("NaN"));
        assertNull(MqttCommands.parseBrightness(""));
        assertNull(MqttCommands.parseBrightness(null));
    }

    /** JSON 转义。 */
    @Test
    public void jsonEscaping() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\\u2028中文\"", Json.quote("a\"b\\c\n\u0001\u2028中文"));
        assertEquals("{\"a\":1,\"b\":true,\"c\":null,\"d\":[\"x\",\"y\"]}",
                new Json().put("a", 1).put("b", true).put("c", (String) null).raw("d", Json.array("x", "y")).build());
    }

    /** 状态 JSON 字段。 */
    @Test
    public void statusJson() {
        KioskStatus.Builder b = KioskStatus.initial().toBuilder();
        b.url = "https://ha.example/home";
        b.brightness = 70;
        b.presence = true;
        b.battery = 88;
        b.charging = true;
        b.chargeState = BatteryState.CHARGING;
        b.mqttConnected = true;
        String json = StatusJson.of(b.build(), KioskConfig.defaults(), true);
        assertTrue(json.contains("\"screen_on\":\"ON\""));
        assertTrue(json.contains("\"presence\":\"ON\""));
        assertTrue(json.contains("\"battery\":88"));
        assertTrue(json.contains("\"charge_state\":\"charging\""));
        assertTrue(json.contains("\"watch_brightness\":70"));
        assertTrue(json.contains("\"standby_brightness\":20"));
        assertTrue(json.contains("\"mqtt_connected\":true"));

        b.battery = -1;
        String noMqtt = StatusJson.of(b.build(), KioskConfig.defaults(), false);
        assertTrue(noMqtt.contains("\"battery\":null"));
        assertFalse(noMqtt.contains("mqtt_connected"));
    }

    /** 自动发现包含全部实体，且挂在同一设备下。 */
    @Test
    public void discoveryEntities() {
        List<MqttDiscovery.Message> messages = MqttDiscovery.build(topics, device, labels, false);
        Set<String> seen = new HashSet<>();
        for (MqttDiscovery.Message m : messages) seen.add(m.topic);
        String[] expected = {
            "binary_sensor/kiosk_ab12cd34/screen_on", "binary_sensor/kiosk_ab12cd34/presence",
            "sensor/kiosk_ab12cd34/battery", "binary_sensor/kiosk_ab12cd34/charging",
            "sensor/kiosk_ab12cd34/charge_state", "sensor/kiosk_ab12cd34/brightness",
            "number/kiosk_ab12cd34/watch_brightness", "sensor/kiosk_ab12cd34/url",
            "button/kiosk_ab12cd34/reload", "button/kiosk_ab12cd34/wake",
        };
        for (String e : expected) assertTrue(e, seen.contains("homeassistant/" + e + "/config"));
        for (MqttDiscovery.Message m : messages) {
            if (m.payload.isEmpty()) continue;
            assertTrue(m.payload.contains("\"identifiers\":[\"kiosk_ab12cd34\"]"));
            assertTrue(m.payload.contains("\"name\":\"居家屏幕\""));
            assertTrue(m.payload.contains("\"availability_topic\":\"home_kiosk/kiosk_ab12cd34/availability\""));
        }
        assertTrue(payloadOf(messages, "homeassistant/binary_sensor/kiosk_ab12cd34/screen_on/config")
                .contains("\"name\":\"亮屏\""));
    }

    /** 不是设备所有者时用空保留消息删除重启按钮；是时发布。 */
    @Test
    public void rebootButtonOnlyForDeviceOwner() {
        String topic = "homeassistant/button/kiosk_ab12cd34/reboot/config";
        assertEquals("", payloadOf(MqttDiscovery.build(topics, device, labels, false), topic));
        String owner = payloadOf(MqttDiscovery.build(topics, device, labels, true), topic);
        assertTrue(owner.contains("\"device_class\":\"restart\""));
        assertTrue(owner.contains("\"command_topic\":\"home_kiosk/kiosk_ab12cd34/cmd/reboot\""));
    }

    /** 按主题查找消息内容。 */
    private static String payloadOf(List<MqttDiscovery.Message> messages, String topic) {
        for (MqttDiscovery.Message m : messages) if (m.topic.equals(topic)) return m.payload;
        throw new AssertionError("missing " + topic);
    }
}
