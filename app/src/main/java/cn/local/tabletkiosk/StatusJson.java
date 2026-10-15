package cn.local.tabletkiosk;

/** 生成状态 JSON，MQTT 状态主题和局域网 /status 共用同一份字段。 */
final class StatusJson {
    private StatusJson() {
    }

    /**
     * @param s 运行状态
     * @param c 当前配置
     * @param includeMqtt 是否附带 MQTT 连接状态，局域网接口需要
     */
    static String of(KioskStatus s, KioskConfig c, boolean includeMqtt) {
        Json j = new Json()
                .put("screen_on", onOff(s.screenOn()))
                .put("presence", onOff(s.presence))
                .raw("battery", s.battery >= 0 ? Integer.toString(s.battery) : "null")
                .put("charging", onOff(s.charging))
                .put("charge_state", s.chargeState)
                .put("brightness", s.brightness)
                .put("watch_brightness", c.watchBrightness)
                .put("standby_brightness", c.standbyBrightness)
                .put("url", s.url)
                .put("device_owner", onOff(s.deviceOwner))
                .put("lock_task", onOff(s.lockTask));
        if (includeMqtt) j.put("mqtt_connected", s.mqttConnected);
        return j.build();
    }

    /** 布尔值转 HA 默认的 ON/OFF。 */
    static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }
}
