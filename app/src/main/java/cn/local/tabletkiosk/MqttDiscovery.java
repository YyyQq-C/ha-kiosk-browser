package cn.local.tabletkiosk;

import java.util.ArrayList;
import java.util.List;

/** 生成 Home Assistant MQTT 自动发现配置；全部实体挂在同一台设备“居家屏幕”下。 */
final class MqttDiscovery {
    /** 设备信息。 */
    static final class Device {
        final String id;
        final String name;
        final String manufacturer;
        final String model;
        final String swVersion;

        /** 保存设备信息。 */
        Device(String id, String name, String manufacturer, String model, String swVersion) {
            this.id = id;
            this.name = name;
            this.manufacturer = manufacturer;
            this.model = model;
            this.swVersion = swVersion;
        }
    }

    /** 一条待发布的消息；payload 为空字符串表示删除该实体的保留配置。 */
    static final class Message {
        final String topic;
        final String payload;

        /** 保存主题和内容。 */
        Message(String topic, String payload) {
            this.topic = topic;
            this.payload = payload;
        }
    }

    private MqttDiscovery() {
    }

    /**
     * 生成全部实体的配置消息。
     *
     * @param t 本设备主题
     * @param d 设备信息
     * @param deviceOwner 是否为设备所有者；不是时删除“重启平板”按钮
     */
    static List<Message> build(MqttTopics t, Device d, boolean deviceOwner) {
        String device = new Json()
                .raw("identifiers", Json.array(d.id))
                .put("name", d.name)
                .put("manufacturer", d.manufacturer)
                .put("model", d.model)
                .put("sw_version", d.swVersion)
                .build();
        List<Message> out = new ArrayList<>();

        out.add(entity(t, d, device, "binary_sensor", "screen_on", "亮屏")
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.screen_on }}")
                .put("icon", "mdi:tablet-dashboard")
                .toMessage());
        out.add(entity(t, d, device, "binary_sensor", "presence", "有人靠近")
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.presence }}")
                .put("device_class", "motion")
                .toMessage());
        out.add(entity(t, d, device, "sensor", "battery", "电量")
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.battery }}")
                .put("device_class", "battery")
                .put("unit_of_measurement", "%")
                .put("state_class", "measurement")
                .toMessage());
        out.add(entity(t, d, device, "binary_sensor", "charging", "充电中")
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.charging }}")
                .put("device_class", "battery_charging")
                .toMessage());
        out.add(entity(t, d, device, "sensor", "charge_state", "充电状态")
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.charge_state }}")
                .put("device_class", "enum")
                .raw("options", Json.array(BatteryState.CHARGING, BatteryState.DISCHARGING,
                        BatteryState.NOT_CHARGING, BatteryState.FULL, BatteryState.UNKNOWN))
                .put("icon", "mdi:battery-charging")
                .toMessage());
        out.add(entity(t, d, device, "sensor", "brightness", "当前亮度")
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.brightness }}")
                .put("unit_of_measurement", "%")
                .put("state_class", "measurement")
                .put("icon", "mdi:brightness-6")
                .toMessage());
        out.add(entity(t, d, device, "number", "watch_brightness", "设置亮度")
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.watch_brightness }}")
                .put("command_topic", t.command(MqttTopics.CMD_BRIGHTNESS))
                .put("min", 1)
                .put("max", 100)
                .put("step", 1)
                .put("mode", "slider")
                .put("unit_of_measurement", "%")
                .put("icon", "mdi:brightness-percent")
                .toMessage());
        out.add(entity(t, d, device, "sensor", "url", "当前网址")
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.url[:255] }}")
                .put("entity_category", "diagnostic")
                .put("icon", "mdi:web")
                .toMessage());
        out.add(entity(t, d, device, "button", "reload", "立即刷新")
                .put("command_topic", t.command(MqttTopics.CMD_RELOAD))
                .put("payload_press", "PRESS")
                .put("icon", "mdi:refresh")
                .toMessage());
        out.add(entity(t, d, device, "button", "wake", "唤醒屏幕")
                .put("command_topic", t.command(MqttTopics.CMD_WAKE))
                .put("payload_press", "PRESS")
                .put("icon", "mdi:gesture-tap")
                .toMessage());
        if (deviceOwner) {
            out.add(entity(t, d, device, "button", "reboot", "重启平板")
                    .put("command_topic", t.command(MqttTopics.CMD_REBOOT))
                    .put("payload_press", "PRESS")
                    .put("device_class", "restart")
                    .put("entity_category", "config")
                    .toMessage());
        } else {
            out.add(new Message(t.discovery("button", "reboot"), ""));
        }
        return out;
    }

    /** 创建实体配置，写入名称、唯一 ID、在线状态和设备信息。 */
    private static Entity entity(MqttTopics t, Device d, String device, String component, String objectId,
                                 String name) {
        Entity e = new Entity(t.discovery(component, objectId));
        e.json.put("name", name)
                .put("unique_id", d.id + "_" + objectId)
                .put("availability_topic", t.availability())
                .raw("device", device);
        return e;
    }

    /** 实体配置构建器。 */
    private static final class Entity {
        final String topic;
        final Json json = new Json();

        /** 记录配置主题。 */
        Entity(String topic) {
            this.topic = topic;
        }

        /** 写入字符串字段。 */
        Entity put(String key, String value) {
            json.put(key, value);
            return this;
        }

        /** 写入整数字段。 */
        Entity put(String key, int value) {
            json.put(key, value);
            return this;
        }

        /** 写入 JSON 片段。 */
        Entity raw(String key, String value) {
            json.raw(key, value);
            return this;
        }

        /** 生成待发布消息。 */
        Message toMessage() {
            return new Message(topic, json.build());
        }
    }
}
