package cn.local.tabletkiosk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 设置文件能带走密码、开关和首页，坏文件不会被当成空配置。 */
public class SettingsFileTest {
    /** 导出后再读回，关键字段保持一致。 */
    @Test
    public void roundTripKeepsPasswordsAndSwitches() {
        KioskConfig.Builder builder = KioskConfig.defaults().toBuilder();
        builder.homeUrl = "https://ha.example:8123/lovelace";
        builder.mqttEnabled = true;
        builder.mqttHost = "192.168.1.10";
        builder.mqttPassword = "secret-mqtt";
        builder.lanEnabled = true;
        builder.lanPassword = "12345678";
        builder.settingsPin = "1234";
        builder.watchBrightness = 40;
        KioskConfig source = builder.build();
        SettingsFile.Document document = SettingsFile.fromJson(SettingsFile.toJson(source, "en", "token-1"));
        assertNull(document.error);
        assertEquals("en", document.language);
        assertEquals("token-1", document.haToken);
        assertEquals(source.homeUrl, document.config.homeUrl);
        assertEquals("secret-mqtt", document.config.mqttPassword);
        assertEquals("12345678", document.config.lanPassword);
        assertEquals("1234", document.config.settingsPin);
        assertEquals(40, document.config.watchBrightness);
        assertTrue(document.config.mqttEnabled);
        assertTrue(document.config.lanEnabled);
    }

    /** 不是本应用写出的文件时拒绝。 */
    @Test
    public void rejectsUnknownFile() {
        SettingsFile.Document document = SettingsFile.fromJson("{\"homeUrl\":\"https://ha.example\"}");
        assertEquals("invalid settings file", document.error);
    }
}
