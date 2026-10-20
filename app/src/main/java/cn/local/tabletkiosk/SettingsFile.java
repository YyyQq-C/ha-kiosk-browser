package cn.local.tabletkiosk;

import java.util.LinkedHashMap;
import java.util.Map;

/** 把本机设置收成一份 JSON，或从这份 JSON 还原。文件里含密码，只应由用户自己保存。 */
public final class SettingsFile {
    public static final String FORMAT = "format";
    public static final String LANGUAGE = "language";
    public static final String HA_TOKEN = "haToken";

    /** 读入后的结果。error 非空时 config 为空。 */
    public static final class Document {
        public final KioskConfig config;
        public final String language;
        public final String haToken;
        public final String error;

        private Document(KioskConfig config, String language, String haToken, String error) {
            this.config = config;
            this.language = language == null ? "" : language;
            this.haToken = haToken == null ? "" : haToken;
            this.error = error;
        }
    }

    private SettingsFile() {
    }

    /** 导出当前配置、界面语言和 Home Assistant 令牌。 */
    public static String toJson(KioskConfig config, String language, String haToken) {
        Json json = new Json();
        json.put(FORMAT, 1);
        json.put(LANGUAGE, language == null ? "" : language);
        json.put(HA_TOKEN, haToken == null ? "" : haToken);
        json.put("motionEnabled", config.motionEnabled);
        json.put("mqttEnabled", config.mqttEnabled);
        json.put("lanEnabled", config.lanEnabled);
        json.put("lockTaskEnabled", config.lockTaskEnabled);
        json.put("scheduleEnabled", config.scheduleEnabled);
        json.put("dailyReloadEnabled", config.dailyReloadEnabled);
        json.put("sleepEnabled", config.sleepEnabled);
        json.put("assistEnabled", config.assistEnabled);
        json.put("screensaverEnabled", config.screensaverEnabled);
        json.put("screensaverWeather", config.screensaverWeather);
        json.put("screensaverLunar", config.screensaverLunar);
        for (Map.Entry<String, String> entry : ConfigForm.toText(config).entrySet()) {
            json.put(entry.getKey(), entry.getValue());
        }
        return json.build();
    }

    /** 还原设置。格式不对或字段不合法时 error 为英文原因。 */
    public static Document fromJson(String json) {
        Map<String, String> raw = Json.readObject(json);
        if (raw == null || !"1".equals(raw.get(FORMAT))) return new Document(null, "", "", "invalid settings file");
        Map<String, String> text = new LinkedHashMap<>(raw);
        ConfigForm.Result result = ConfigForm.parse(text, flag(raw, "motionEnabled"), flag(raw, "mqttEnabled"),
                flag(raw, "lanEnabled"), flag(raw, "lockTaskEnabled"), flag(raw, "scheduleEnabled"),
                flag(raw, "dailyReloadEnabled"), flag(raw, "sleepEnabled"), flag(raw, "assistEnabled"),
                flag(raw, "screensaverEnabled", true), flag(raw, "screensaverWeather", true),
                flag(raw, "screensaverLunar", true));
        if (result.config == null) return new Document(null, "", "", "invalid settings values");
        return new Document(result.config, raw.get(LANGUAGE), raw.get(HA_TOKEN), null);
    }

    /** JSON 布尔文本。缺键视为 false。 */
    private static boolean flag(Map<String, String> raw, String key) {
        return flag(raw, key, false);
    }

    /** JSON 布尔文本。缺键时用 fallback，避免旧文件把新增的屏保开关读成关闭。 */
    private static boolean flag(Map<String, String> raw, String key, boolean fallback) {
        if (!raw.containsKey(key)) return fallback;
        return "true".equals(raw.get(key));
    }
}
