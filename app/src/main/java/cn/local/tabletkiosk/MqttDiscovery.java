package cn.local.tabletkiosk;

import java.util.ArrayList;
import java.util.List;

/** 生成 Home Assistant MQTT 自动发现配置；全部实体挂在同一台设备“HA 智能面板”下。 */
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

    /** 各实体在 Home Assistant 中显示的名称，由界面语言决定。 */
    static final class Labels {
        final String screenOn;
        final String presence;
        final String battery;
        final String charging;
        final String chargeState;
        final String brightness;
        final String watchBrightness;
        final String url;
        final String reload;
        final String wake;
        final String reboot;
        final String standby;
        final String motion;
        final String standbyBrightness;
        final String dimDelay;
        final String lockTask;
        final String screenOff;
        final String home;
        final String openUrl;
        final String screenshot;
        final String notify;
        final String speak;
        final String schedule;
        final String dayBrightness;
        final String nightBrightness;
        final String ip;
        final String appVersion;
        final String model;
        final String androidVersion;
        final String volume;
        final String play;
        final String dailyReload;
        final String dailyHour;
        final String dailyMinute;
        final String sleep;
        final String sleepStart;
        final String sleepEnd;
        final String watchdog;
        final String wifiSsid;
        final String wifiSignal;
        final String cpu;
        final String memory;
        final String memoryLow;
        final String storage;
        final String appMemory;
        final String temperature;

        /** 保存全部实体名称。 */
        Labels(String screenOn, String presence, String battery, String charging, String chargeState,
               String brightness, String watchBrightness, String url, String reload, String wake, String reboot,
               String standby, String motion, String standbyBrightness, String dimDelay, String lockTask,
               String screenOff, String home, String openUrl, String screenshot, String notify, String speak,
               String schedule, String dayBrightness, String nightBrightness, String ip, String appVersion,
               String model, String androidVersion, String volume, String play, String dailyReload,
               String dailyHour, String dailyMinute, String sleep, String sleepStart, String sleepEnd,
               String watchdog, String wifiSsid, String wifiSignal, String cpu, String memory, String memoryLow,
               String storage, String appMemory, String temperature) {
            this.screenOn = screenOn;
            this.presence = presence;
            this.battery = battery;
            this.charging = charging;
            this.chargeState = chargeState;
            this.brightness = brightness;
            this.watchBrightness = watchBrightness;
            this.url = url;
            this.reload = reload;
            this.wake = wake;
            this.reboot = reboot;
            this.standby = standby;
            this.motion = motion;
            this.standbyBrightness = standbyBrightness;
            this.dimDelay = dimDelay;
            this.lockTask = lockTask;
            this.screenOff = screenOff;
            this.home = home;
            this.openUrl = openUrl;
            this.screenshot = screenshot;
            this.notify = notify;
            this.speak = speak;
            this.schedule = schedule;
            this.dayBrightness = dayBrightness;
            this.nightBrightness = nightBrightness;
            this.ip = ip;
            this.appVersion = appVersion;
            this.model = model;
            this.androidVersion = androidVersion;
            this.volume = volume;
            this.play = play;
            this.dailyReload = dailyReload;
            this.dailyHour = dailyHour;
            this.dailyMinute = dailyMinute;
            this.sleep = sleep;
            this.sleepStart = sleepStart;
            this.sleepEnd = sleepEnd;
            this.watchdog = watchdog;
            this.wifiSsid = wifiSsid;
            this.wifiSignal = wifiSignal;
            this.cpu = cpu;
            this.memory = memory;
            this.memoryLow = memoryLow;
            this.storage = storage;
            this.appMemory = appMemory;
            this.temperature = temperature;
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
     * @param names 实体显示名称
     * @param deviceOwner 是否为设备所有者；不是时删除重启按钮
     */
    static List<Message> build(MqttTopics t, Device d, Labels names, boolean deviceOwner) {
        String device = new Json()
                .raw("identifiers", Json.array(d.id))
                .put("name", d.name)
                .put("manufacturer", d.manufacturer)
                .put("model", d.model)
                .put("sw_version", d.swVersion)
                .build();
        List<Message> out = new ArrayList<>();

        out.add(entity(t, d, device, "binary_sensor", "screen_on", names.screenOn)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.screen_on }}")
                .put("icon", "mdi:tablet-dashboard")
                .toMessage());
        out.add(entity(t, d, device, "binary_sensor", "presence", names.presence)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.presence }}")
                .put("device_class", "motion")
                .toMessage());
        out.add(entity(t, d, device, "sensor", "battery", names.battery)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.battery }}")
                .put("device_class", "battery")
                .put("unit_of_measurement", "%")
                .put("state_class", "measurement")
                .toMessage());
        out.add(entity(t, d, device, "binary_sensor", "charging", names.charging)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.charging }}")
                .put("device_class", "battery_charging")
                .toMessage());
        out.add(entity(t, d, device, "sensor", "charge_state", names.chargeState)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.charge_state }}")
                .put("device_class", "enum")
                .raw("options", Json.array(BatteryState.CHARGING, BatteryState.DISCHARGING,
                        BatteryState.NOT_CHARGING, BatteryState.FULL, BatteryState.UNKNOWN))
                .put("icon", "mdi:battery-charging")
                .toMessage());
        out.add(entity(t, d, device, "sensor", "brightness", names.brightness)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.brightness }}")
                .put("unit_of_measurement", "%")
                .put("state_class", "measurement")
                .put("icon", "mdi:brightness-6")
                .toMessage());
        out.add(entity(t, d, device, "number", "watch_brightness", names.watchBrightness)
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
        out.add(entity(t, d, device, "sensor", "url", names.url)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.url[:255] }}")
                .put("entity_category", "diagnostic")
                .put("icon", "mdi:web")
                .toMessage());
        out.add(entity(t, d, device, "button", "reload", names.reload)
                .put("command_topic", t.command(MqttTopics.CMD_RELOAD))
                .put("payload_press", "PRESS")
                .put("icon", "mdi:refresh")
                .toMessage());
        out.add(entity(t, d, device, "button", "wake", names.wake)
                .put("command_topic", t.command(MqttTopics.CMD_WAKE))
                .put("payload_press", "PRESS")
                .put("icon", "mdi:gesture-tap")
                .toMessage());
        out.add(entity(t, d, device, "binary_sensor", "standby", names.standby)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.standby }}")
                .put("icon", "mdi:theme-light-dark")
                .toMessage());
        out.add(entity(t, d, device, "switch", "motion", names.motion)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.motion }}")
                .put("command_topic", t.command(MqttTopics.CMD_MOTION))
                .put("payload_on", "ON")
                .put("payload_off", "OFF")
                .put("icon", "mdi:motion-sensor")
                .toMessage());
        out.add(entity(t, d, device, "number", "standby_brightness", names.standbyBrightness)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.standby_brightness }}")
                .put("command_topic", t.command(MqttTopics.CMD_STANDBY))
                .put("min", 0)
                .put("max", 100)
                .put("step", 1)
                .put("unit_of_measurement", "%")
                .put("icon", "mdi:brightness-5")
                .toMessage());
        out.add(entity(t, d, device, "number", "dim_delay", names.dimDelay)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.dim_delay }}")
                .put("command_topic", t.command(MqttTopics.CMD_DIM_DELAY))
                .put("min", 0)
                .put("max", 86400)
                .put("step", 1)
                .put("unit_of_measurement", "s")
                .put("icon", "mdi:timer-outline")
                .toMessage());
        out.add(entity(t, d, device, "switch", "lock_task", names.lockTask)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.lock_task }}")
                .put("command_topic", t.command(MqttTopics.CMD_LOCK))
                .put("payload_on", "ON")
                .put("payload_off", "OFF")
                .put("icon", "mdi:lock")
                .toMessage());
        out.add(entity(t, d, device, "button", "screen_off", names.screenOff)
                .put("command_topic", t.command(MqttTopics.CMD_SCREEN_OFF))
                .put("payload_press", "PRESS")
                .put("icon", "mdi:monitor-off")
                .toMessage());
        out.add(entity(t, d, device, "button", "home", names.home)
                .put("command_topic", t.command(MqttTopics.CMD_HOME))
                .put("payload_press", "PRESS")
                .put("icon", "mdi:home")
                .toMessage());
        out.add(entity(t, d, device, "text", "url_command", names.openUrl)
                .put("command_topic", t.command(MqttTopics.CMD_URL))
                .put("mode", "text")
                .put("max", 2000)
                .put("icon", "mdi:web")
                .toMessage());
        out.add(entity(t, d, device, "button", "screenshot", names.screenshot)
                .put("command_topic", t.command(MqttTopics.CMD_SCREENSHOT))
                .put("payload_press", "PRESS")
                .put("icon", "mdi:monitor-screenshot")
                .toMessage());
        out.add(entity(t, d, device, "camera", "screen", names.screenshot)
                .put("topic", t.camera())
                .put("icon", "mdi:monitor-screenshot")
                .toMessage());
        out.add(entity(t, d, device, "text", "notify", names.notify)
                .put("command_topic", t.command(MqttTopics.CMD_NOTIFY))
                .put("mode", "text")
                .put("max", 200)
                .put("icon", "mdi:message-text")
                .toMessage());
        out.add(entity(t, d, device, "text", "tts", names.speak)
                .put("command_topic", t.command(MqttTopics.CMD_TTS))
                .put("mode", "text")
                .put("max", 200)
                .put("icon", "mdi:account-voice")
                .toMessage());
        out.add(entity(t, d, device, "switch", "schedule", names.schedule)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.schedule }}")
                .put("command_topic", t.command(MqttTopics.CMD_SCHEDULE))
                .put("payload_on", "ON")
                .put("payload_off", "OFF")
                .put("icon", "mdi:calendar-clock")
                .toMessage());
        out.add(entity(t, d, device, "number", "day_brightness", names.dayBrightness)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.day_brightness }}")
                .put("command_topic", t.command(MqttTopics.CMD_DAY))
                .put("min", 1)
                .put("max", 100)
                .put("step", 1)
                .put("unit_of_measurement", "%")
                .put("icon", "mdi:white-balance-sunny")
                .toMessage());
        out.add(entity(t, d, device, "number", "night_brightness", names.nightBrightness)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.night_brightness }}")
                .put("command_topic", t.command(MqttTopics.CMD_NIGHT))
                .put("min", 1)
                .put("max", 100)
                .put("step", 1)
                .put("unit_of_measurement", "%")
                .put("icon", "mdi:weather-night")
                .toMessage());
        out.add(entity(t, d, device, "sensor", "ip", names.ip)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.ip }}")
                .put("icon", "mdi:ip-network")
                .put("entity_category", "diagnostic")
                .toMessage());
        out.add(entity(t, d, device, "sensor", "app_version", names.appVersion)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.app_version }}")
                .put("icon", "mdi:application")
                .put("entity_category", "diagnostic")
                .toMessage());
        out.add(entity(t, d, device, "sensor", "model", names.model)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.model }}")
                .put("icon", "mdi:tablet")
                .put("entity_category", "diagnostic")
                .toMessage());
        out.add(entity(t, d, device, "sensor", "android_version", names.androidVersion)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.android_version }}")
                .put("icon", "mdi:android")
                .put("entity_category", "diagnostic")
                .toMessage());
        out.add(entity(t, d, device, "number", "volume", names.volume)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.volume }}")
                .put("command_topic", t.command(MqttTopics.CMD_VOLUME))
                .put("min", 0)
                .put("max", 100)
                .put("step", 1)
                .put("unit_of_measurement", "%")
                .put("icon", "mdi:volume-high")
                .toMessage());
        out.add(entity(t, d, device, "text", "play", names.play)
                .put("command_topic", t.command(MqttTopics.CMD_PLAY))
                .put("mode", "text")
                .put("max", 2000)
                .put("icon", "mdi:music")
                .toMessage());
        out.add(entity(t, d, device, "switch", "daily_reload", names.dailyReload)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.daily_reload }}")
                .put("command_topic", t.command(MqttTopics.CMD_DAILY))
                .put("payload_on", "ON")
                .put("payload_off", "OFF")
                .put("icon", "mdi:calendar-refresh")
                .toMessage());
        out.add(entity(t, d, device, "number", "daily_hour", names.dailyHour)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.daily_hour }}")
                .put("command_topic", t.command(MqttTopics.CMD_DAILY_HOUR))
                .put("min", 0)
                .put("max", 23)
                .put("step", 1)
                .put("icon", "mdi:clock-outline")
                .toMessage());
        out.add(entity(t, d, device, "number", "daily_minute", names.dailyMinute)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.daily_minute }}")
                .put("command_topic", t.command(MqttTopics.CMD_DAILY_MINUTE))
                .put("min", 0)
                .put("max", 59)
                .put("step", 1)
                .put("icon", "mdi:clock-outline")
                .toMessage());
        out.add(entity(t, d, device, "switch", "sleep", names.sleep)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.sleep }}")
                .put("command_topic", t.command(MqttTopics.CMD_SLEEP))
                .put("payload_on", "ON")
                .put("payload_off", "OFF")
                .put("icon", "mdi:sleep")
                .toMessage());
        out.add(entity(t, d, device, "number", "sleep_start", names.sleepStart)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.sleep_start }}")
                .put("command_topic", t.command(MqttTopics.CMD_SLEEP_START))
                .put("min", 0)
                .put("max", 23)
                .put("step", 1)
                .put("icon", "mdi:weather-night")
                .toMessage());
        out.add(entity(t, d, device, "number", "sleep_end", names.sleepEnd)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.sleep_end }}")
                .put("command_topic", t.command(MqttTopics.CMD_SLEEP_END))
                .put("min", 0)
                .put("max", 23)
                .put("step", 1)
                .put("icon", "mdi:weather-sunny")
                .toMessage());
        out.add(entity(t, d, device, "number", "watchdog", names.watchdog)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.watchdog }}")
                .put("command_topic", t.command(MqttTopics.CMD_WATCHDOG))
                .put("min", 0)
                .put("max", 3600)
                .put("step", 1)
                .put("unit_of_measurement", "s")
                .put("icon", "mdi:timer-alert")
                .toMessage());
        out.add(entity(t, d, device, "sensor", "wifi_ssid", names.wifiSsid)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.wifi_ssid }}")
                .put("icon", "mdi:wifi")
                .put("entity_category", "diagnostic")
                .toMessage());
        out.add(entity(t, d, device, "sensor", "wifi_signal", names.wifiSignal)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.wifi_rssi }}")
                .put("device_class", "signal_strength")
                .put("unit_of_measurement", "dBm")
                .put("entity_category", "diagnostic")
                .toMessage());
        out.add(percentSensor(t, d, device, "cpu", names.cpu, "{{ value_json.cpu }}", "mdi:cpu-64-bit"));
        out.add(percentSensor(t, d, device, "memory", names.memory, "{{ value_json.memory }}", "mdi:memory"));
        out.add(entity(t, d, device, "binary_sensor", "memory_low", names.memoryLow)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.memory_low }}")
                .put("device_class", "problem")
                .put("icon", "mdi:memory-alert")
                .toMessage());
        out.add(percentSensor(t, d, device, "storage", names.storage, "{{ value_json.storage }}", "mdi:database"));
        out.add(entity(t, d, device, "sensor", "app_memory", names.appMemory)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.app_memory_mb }}")
                .put("unit_of_measurement", "MB")
                .put("state_class", "measurement")
                .put("icon", "mdi:application-cog")
                .put("entity_category", "diagnostic")
                .toMessage());
        out.add(entity(t, d, device, "sensor", "temperature", names.temperature)
                .put("state_topic", t.state())
                .put("value_template", "{{ value_json.temperature }}")
                .put("device_class", "temperature")
                .put("unit_of_measurement", "°C")
                .put("state_class", "measurement")
                .put("icon", "mdi:thermometer")
                .toMessage());
        if (deviceOwner) {
            out.add(entity(t, d, device, "button", "reboot", names.reboot)
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

    /** 百分比传感器。 */
    private static Message percentSensor(MqttTopics t, Device d, String device, String objectId, String name,
                                         String template, String icon) {
        return entity(t, d, device, "sensor", objectId, name)
                .put("state_topic", t.state())
                .put("value_template", template)
                .put("unit_of_measurement", "%")
                .put("state_class", "measurement")
                .put("icon", icon)
                .toMessage();
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
