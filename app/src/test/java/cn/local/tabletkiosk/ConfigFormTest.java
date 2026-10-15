package cn.local.tabletkiosk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

/** 设置页输入校验、默认值和电池状态换算。 */
public class ConfigFormTest {
    /** 默认配置与计划给出的初始值一致。 */
    @Test
    public void defaultsMatchPlan() {
        KioskConfig c = KioskConfig.defaults();
        assertEquals("http://homeassistant.local:8123/", c.homeUrl);
        assertEquals(12, c.idleReloadHours);
        assertEquals(30, c.retrySeconds);
        assertEquals(70, c.watchBrightness);
        assertEquals(20, c.standbyBrightness);
        assertEquals(60, c.dimDelaySeconds);
        assertEquals(50, c.motionSensitivity);
        assertTrue(c.motionEnabled);
        assertEquals(1883, c.mqttPort);
        assertEquals("homeassistant", c.mqttDiscoveryPrefix);
        assertEquals(60, c.mqttStateIntervalSeconds);
        assertEquals(2323, c.lanPort);
    }

    /** 默认值转成文字后能原样解析回来。 */
    @Test
    public void roundTripsDefaults() {
        KioskConfig c = KioskConfig.defaults();
        ConfigForm.Result r = ConfigForm.parse(ConfigForm.toText(c), c.motionEnabled, false, false, c.lockTaskEnabled);
        assertTrue(r.errors.toString(), r.ok());
        assertEquals(c, r.config);
    }

    /** 0 表示关闭，属于合法值。 */
    @Test
    public void zeroDisablesFeatures() {
        Map<String, String> t = ConfigForm.toText(KioskConfig.defaults());
        t.put(ConfigForm.IDLE_RELOAD_HOURS, "0");
        t.put(ConfigForm.RETRY_SECONDS, "0");
        t.put(ConfigForm.STANDBY_BRIGHTNESS, "0");
        t.put(ConfigForm.DIM_DELAY_SECONDS, "0");
        t.put(ConfigForm.MOTION_SENSITIVITY, "0");
        t.put(ConfigForm.MQTT_STATE_INTERVAL, "0");
        ConfigForm.Result r = ConfigForm.parse(t, true, false, false, true);
        assertTrue(r.errors.toString(), r.ok());
        assertEquals(0, r.config.idleReloadHours);
    }

    /** 越界、非数字、错误网址和错误主题都会给出提示。 */
    @Test
    public void reportsInvalidFields() {
        Map<String, String> t = ConfigForm.toText(KioskConfig.defaults());
        t.put(ConfigForm.HOME_URL, "ftp://x");
        t.put(ConfigForm.IDLE_RELOAD_HOURS, "721");
        t.put(ConfigForm.WATCH_BRIGHTNESS, "0");
        t.put(ConfigForm.RETRY_SECONDS, "abc");
        t.put(ConfigForm.LAN_PORT, "80");
        t.put(ConfigForm.MQTT_BASE_TOPIC, "bad topic/#");
        t.put(ConfigForm.MQTT_DISCOVERY_PREFIX, "/homeassistant");
        ConfigForm.Result r = ConfigForm.parse(t, true, false, false, true);
        assertFalse(r.ok());
        for (String key : new String[] {ConfigForm.HOME_URL, ConfigForm.IDLE_RELOAD_HOURS,
                ConfigForm.WATCH_BRIGHTNESS, ConfigForm.RETRY_SECONDS, ConfigForm.LAN_PORT,
                ConfigForm.MQTT_BASE_TOPIC, ConfigForm.MQTT_DISCOVERY_PREFIX}) {
            assertTrue(key, r.errors.containsKey(key));
        }
    }

    /** 开启 MQTT 需要主机；开启局域网需要至少 8 位密码。 */
    @Test
    public void enabledFeaturesRequireSettings() {
        Map<String, String> t = ConfigForm.toText(KioskConfig.defaults());
        t.put(ConfigForm.MQTT_HOST, "");
        t.put(ConfigForm.LAN_PASSWORD, "short");
        ConfigForm.Result r = ConfigForm.parse(t, true, true, true, true);
        assertTrue(r.errors.containsKey(ConfigForm.MQTT_HOST));
        assertTrue(r.errors.containsKey(ConfigForm.LAN_PASSWORD));

        t.put(ConfigForm.MQTT_HOST, "192.168.1.16");
        t.put(ConfigForm.LAN_PASSWORD, "long-enough");
        r = ConfigForm.parse(t, true, true, true, true);
        assertTrue(r.errors.toString(), r.ok());
        assertEquals("192.168.1.16", r.config.mqttHost);
    }

    /** 电池广播换算。 */
    @Test
    public void batteryState() {
        BatteryState b = BatteryState.from(45, 50, BatteryState.STATUS_CHARGING);
        assertEquals(90, b.percent);
        assertTrue(b.charging);
        assertEquals(BatteryState.CHARGING, b.state);
        assertEquals(BatteryState.FULL, BatteryState.from(100, 100, BatteryState.STATUS_FULL).state);
        assertFalse(BatteryState.from(100, 100, BatteryState.STATUS_FULL).charging);
        assertEquals(BatteryState.NOT_CHARGING, BatteryState.from(80, 100, BatteryState.STATUS_NOT_CHARGING).state);
        assertEquals(BatteryState.DISCHARGING, BatteryState.from(80, 100, BatteryState.STATUS_DISCHARGING).state);
        BatteryState unknown = BatteryState.from(-1, -1, 1);
        assertEquals(-1, unknown.percent);
        assertEquals(BatteryState.UNKNOWN, unknown.state);
    }
}
