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
                .put("dim_delay", c.dimDelaySeconds)
                .put("standby", onOff(!s.watching))
                .put("motion", onOff(c.motionEnabled))
                .put("schedule", onOff(c.scheduleEnabled))
                .put("day_brightness", c.dayBrightness)
                .put("night_brightness", c.nightBrightness)
                .put("url", s.url)
                .put("ip", s.ip)
                .put("app_version", s.appVersion)
                .put("model", s.model)
                .put("android_version", s.androidVersion)
                .put("volume", s.volume)
                .put("wifi_ssid", s.wifiSsid)
                .raw("wifi_rssi", s.wifiRssi > -127 && s.wifiRssi < 0 ? Integer.toString(s.wifiRssi) : "null")
                .raw("cpu", unknownNumber(s.cpuPercent))
                .raw("memory", unknownNumber(s.memoryPercent))
                .raw("memory_used_mb", unknownNumber(s.memoryUsedMb))
                .raw("memory_total_mb", unknownNumber(s.memoryTotalMb))
                .put("memory_low", onOff(s.memoryLow))
                .raw("storage", unknownNumber(s.storagePercent))
                .raw("app_memory_mb", unknownNumber(s.appMemoryMb))
                .raw("temperature", celsius(s.batteryTempTenths))
                .put("daily_reload", onOff(c.dailyReloadEnabled))
                .put("daily_hour", c.dailyReloadHour)
                .put("daily_minute", c.dailyReloadMinute)
                .put("sleep", onOff(c.sleepEnabled))
                .put("sleep_start", c.sleepStartHour)
                .put("sleep_end", c.sleepEndHour)
                .put("watchdog", c.watchdogSeconds)
                .put("device_owner", onOff(s.deviceOwner))
                .put("lock_task", onOff(s.lockTask));
        if (includeMqtt) j.put("mqtt_connected", s.mqttConnected);
        return j.build();
    }

    /** 布尔值转 HA 默认的 ON/OFF。 */
    static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    /** 负数表示尚未读到，写成 JSON null。 */
    private static String unknownNumber(int value) {
        return value < 0 ? "null" : Integer.toString(value);
    }

    /** 电池温度以 0.1 摄氏度为单位，未知写成 JSON null。 */
    static String celsius(int tenths) {
        if (tenths < -200) return "null";
        int abs = Math.abs(tenths);
        return (tenths < 0 ? "-" : "") + (abs / 10) + "." + (abs % 10);
    }
}
