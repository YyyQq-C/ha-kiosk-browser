package cn.local.tabletkiosk;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** 对照 GitHub Release 判断有没有更新的正式包。 */
public final class AppUpdate {
    static final String LATEST = "https://api.github.com/repos/YyyQq-C/ha-kiosk-browser/releases/latest";

    /** 一条已发布版本。apkUrl 为空时只能打开发布页。 */
    public static final class Release {
        public final String version;
        public final String apkUrl;
        public final String pageUrl;

        /** 保存比较用的版本号和下载地址。 */
        public Release(String version, String apkUrl, String pageUrl) {
            this.version = version == null ? "" : version;
            this.apkUrl = apkUrl == null ? "" : apkUrl;
            this.pageUrl = pageUrl == null ? "" : pageUrl;
        }
    }

    private AppUpdate() {
    }

    /** 读取最新正式版。网络失败时返回 null，不抛给界面。 */
    public static Release latest() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(LATEST).toURL().openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "ha-kiosk-browser");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                Log.w(KioskApp.LOG, "Update check HTTP status=" + status);
                return null;
            }
            return parse(read(connection.getInputStream()));
        } catch (Exception e) {
            Log.w(KioskApp.LOG, "Update check failed", e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** 远端版本号比当前大时返回 true。无法比较时返回 false。 */
    public static boolean newer(String current, String remote) {
        int[] left = parts(current);
        int[] right = parts(remote);
        if (left == null || right == null) return false;
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            if (a != b) return b > a;
        }
        return false;
    }

    /** 从最新发布的 JSON 取出版本、APK 和发布页。 */
    static Release parse(String json) {
        if (json == null || json.isEmpty()) return null;
        String tag = jsonString(json, "tag_name");
        if (tag.startsWith("v") || tag.startsWith("V")) tag = tag.substring(1);
        if (tag.isEmpty()) return null;
        String page = jsonString(json, "html_url");
        String apk = "";
        int from = 0;
        while (from < json.length()) {
            String url = jsonStringAt(json, "browser_download_url", from);
            if (url.isEmpty()) break;
            if (url.endsWith(".apk")) {
                apk = url;
                break;
            }
            from = json.indexOf("browser_download_url", from + 1);
            if (from < 0) break;
        }
        return new Release(tag, apk, page);
    }

    /** 把版本拆成整数段。带 v 前缀或空串时返回 null。 */
    private static int[] parts(String version) {
        if (version == null) return null;
        String text = version.trim();
        if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1);
        if (text.isEmpty()) return null;
        String[] raw = text.split("\\.");
        int[] values = new int[raw.length];
        for (int i = 0; i < raw.length; i++) {
            int end = 0;
            while (end < raw[i].length() && Character.isDigit(raw[i].charAt(end))) end++;
            if (end == 0) return null;
            try {
                values[i] = Integer.parseInt(raw[i].substring(0, end));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return values;
    }

    /** 读取对象里第一个指定字符串字段。 */
    private static String jsonString(String json, String key) {
        return jsonStringAt(json, key, 0);
    }

    /** 从指定位置后读取下一个字符串字段。 */
    private static String jsonStringAt(String json, String key, int start) {
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle, start);
        if (at < 0) return "";
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) return "";
        int open = json.indexOf('"', colon + 1);
        if (open < 0) return "";
        StringBuilder value = new StringBuilder();
        for (int i = open + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                value.append(json.charAt(i + 1));
                i++;
                continue;
            }
            if (c == '"') return value.toString();
            value.append(c);
        }
        return "";
    }

    /** 读完响应正文。 */
    private static String read(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int count;
        while ((count = input.read(buffer)) >= 0) out.write(buffer, 0, count);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
