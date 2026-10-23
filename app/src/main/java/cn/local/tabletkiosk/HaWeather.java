package cn.local.tabletkiosk;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 读取 Home Assistant 里已经存在的天气实体，不另接天气供应商。 */
final class HaWeather {
    /** 一次可用的天气。温度为 null 表示实体没有给出温度。 */
    static final class Snapshot {
        final String state;
        final Double temperature;
        final String unit;

        Snapshot(String state, Double temperature, String unit) {
            this.state = state == null ? "" : state;
            this.temperature = temperature;
            this.unit = unit == null ? "" : unit;
        }
    }

    private HaWeather() {
    }

    /** 请求 /api/states。令牌和响应正文不写入日志。 */
    static Snapshot fetch(String homeUrl, String token) {
        String origin = HaDashboards.originOf(homeUrl);
        if (origin == null || token == null || token.trim().isEmpty()) return null;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(origin + "/api/states").toURL().openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Authorization", "Bearer " + token.trim());
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                Log.w(KioskApp.LOG, "Weather states HTTP status=" + status);
                return null;
            }
            return parse(read(connection.getInputStream()));
        } catch (Exception e) {
            Log.w(KioskApp.LOG, "Weather states request failed", e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * 从全部实体里选天气。优先和风实体，否则用第一个可用的 weather.*。
     * 不可用或未知的实体跳过。按字段扫描，避免单元测试里的 org.json 空实现。
     */
    static Snapshot parse(String body) {
        if (body == null || body.isEmpty()) return null;
        Snapshot preferred = null;
        Snapshot first = null;
        int cursor = 0;
        while (cursor < body.length()) {
            int at = body.indexOf("\"entity_id\"", cursor);
            if (at < 0) break;
            int next = body.indexOf("\"entity_id\"", at + 11);
            String slice = body.substring(at, next < 0 ? body.length() : next);
            cursor = next < 0 ? body.length() : next;
            String id = stringField(slice, "\"entity_id\"");
            if (!id.startsWith("weather.")) continue;
            String state = stringField(slice, "\"state\"");
            if (state.isEmpty() || "unavailable".equals(state) || "unknown".equals(state)) continue;
            Snapshot snapshot = new Snapshot(state, numberField(slice, "\"temperature\""),
                    stringField(slice, "\"temperature_unit\""));
            if (first == null) first = snapshot;
            String lower = id.toLowerCase(Locale.ROOT);
            if (preferred == null && (lower.contains("he_feng") || lower.contains("heweather"))) {
                preferred = snapshot;
            }
        }
        return preferred != null ? preferred : first;
    }

    /** 读取键后面的 JSON 字符串。键必须带引号，避免 temperature 匹配到 temperature_unit。 */
    private static String stringField(String slice, String quotedKey) {
        int at = slice.indexOf(quotedKey);
        if (at < 0) return "";
        int colon = slice.indexOf(':', at + quotedKey.length());
        if (colon < 0) return "";
        int i = colon + 1;
        while (i < slice.length() && slice.charAt(i) <= ' ') i++;
        if (i >= slice.length() || slice.charAt(i) != '"') return "";
        i++;
        StringBuilder out = new StringBuilder();
        while (i < slice.length()) {
            char c = slice.charAt(i);
            if (c == '\\' && i + 1 < slice.length()) {
                out.append(slice.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '"') return out.toString();
            out.append(c);
            i++;
        }
        return "";
    }

    /** 读取键后面的数字。null 或缺失返回 null，不当成 0。 */
    private static Double numberField(String slice, String quotedKey) {
        int at = slice.indexOf(quotedKey);
        if (at < 0) return null;
        int colon = slice.indexOf(':', at + quotedKey.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < slice.length() && slice.charAt(i) <= ' ') i++;
        if (i >= slice.length() || slice.charAt(i) == 'n') return null;
        int start = i;
        if (slice.charAt(i) == '-') i++;
        boolean digit = false;
        while (i < slice.length()) {
            char c = slice.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.') {
                digit = true;
                i++;
            } else break;
        }
        if (!digit) return null;
        try {
            double value = Double.parseDouble(slice.substring(start, i));
            if (Double.isNaN(value) || Double.isInfinite(value)) return null;
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 拼成「23°C 晴」。没有温度时只返回天气状况。无法识别时返回 null。 */
    static String display(Snapshot snapshot, boolean chinese) {
        if (snapshot == null) return null;
        String condition = condition(snapshot.state, chinese);
        if (condition == null) return null;
        if (snapshot.temperature == null) return condition;
        return temperatureText(snapshot.temperature, snapshot.unit) + " " + condition;
    }

    /** 天气状况的中文或英文。未知状态返回 null。 */
    static String condition(String state, boolean chinese) {
        if (state == null) return null;
        switch (state) {
            case "sunny":
                return chinese ? "晴" : "Sunny";
            case "clear-night":
                return chinese ? "晴朗" : "Clear";
            case "partlycloudy":
                return chinese ? "多云" : "Partly cloudy";
            case "cloudy":
                return chinese ? "阴" : "Cloudy";
            case "rainy":
                return chinese ? "雨" : "Rain";
            case "pouring":
                return chinese ? "暴雨" : "Pouring";
            case "lightning":
                return chinese ? "雷电" : "Lightning";
            case "lightning-rainy":
                return chinese ? "雷雨" : "Storm";
            case "fog":
                return chinese ? "雾" : "Fog";
            case "snowy":
                return chinese ? "雪" : "Snow";
            case "snowy-rainy":
                return chinese ? "雨夹雪" : "Sleet";
            case "hail":
                return chinese ? "冰雹" : "Hail";
            case "windy":
                return chinese ? "大风" : "Windy";
            case "windy-variant":
                return chinese ? "风伴多云" : "Windy";
            case "exceptional":
                return chinese ? "异常天气" : "Exceptional";
            default:
                return null;
        }
    }

    /** 整数不带小数。单位沿用实体自己的写法。 */
    static String temperatureText(double temperature, String unit) {
        String number = Math.abs(temperature - Math.rint(temperature)) < 0.05
                ? Long.toString(Math.round(temperature))
                : String.format(Locale.US, "%.1f", temperature);
        if (unit == null || unit.isEmpty()) return number + "°";
        return number + unit;
    }

    /** 读完整响应。 */
    private static String read(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) out.write(buffer, 0, count);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
