package cn.local.tabletkiosk;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** 用长期访问令牌读取 Home Assistant 的 Lovelace 仪表盘。 */
public final class HaDashboards {
    /** 一个可选的仪表盘。 */
    public static final class Item {
        public final String title;
        public final String url;

        /** 标题和将写入首页的完整地址。 */
        public Item(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    /** 读取结果。error 为 unauthorized、unreachable 或 bad_response。 */
    public static final class Outcome {
        public final List<Item> items;
        public final String error;

        private Outcome(List<Item> items, String error) {
            this.items = items;
            this.error = error;
        }
    }

    private HaDashboards() {
    }

    /** 请求 /api/lovelace/dashboards。令牌不会写入日志。 */
    public static Outcome load(String homeUrl, String token) {
        String origin = originOf(homeUrl);
        if (origin == null || token == null || token.trim().isEmpty()) {
            return new Outcome(List.of(), "unauthorized");
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(origin + "/api/lovelace/dashboards").toURL().openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("Authorization", "Bearer " + token.trim());
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) return new Outcome(List.of(), "unauthorized");
            if (status < 200 || status >= 300) {
                Log.w(KioskApp.LOG, "Dashboard list HTTP status=" + status);
                return new Outcome(List.of(), "bad_response");
            }
            String body = read(connection.getInputStream());
            return new Outcome(parse(origin, body), null);
        } catch (Exception e) {
            Log.w(KioskApp.LOG, "Dashboard list failed", e);
            return new Outcome(List.of(), "unreachable");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** 从首页地址取出 scheme 和主机。 */
    static String originOf(String homeUrl) {
        try {
            URI uri = URI.create(homeUrl.trim());
            if (uri.getScheme() == null || uri.getRawAuthority() == null) return null;
            String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) return null;
            return scheme + "://" + uri.getRawAuthority();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 把仪表盘 url_path 拼成首页地址。 */
    static String pageUrl(String origin, String urlPath) {
        String path = urlPath == null ? "" : urlPath.trim();
        if (path.isEmpty()) return origin + "/";
        if (path.startsWith("/")) return origin + path;
        return origin + "/" + path;
    }

    /** 解析仪表盘数组。没有标题时用路径。 */
    static List<Item> parse(String origin, String body) {
        List<Item> items = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(body);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String path = item.optString("url_path", "");
                String title = item.optString("title", "");
                if (title.isEmpty()) title = path.isEmpty() ? "/" : path;
                items.add(new Item(title, pageUrl(origin, path)));
            }
        } catch (Exception e) {
            Log.w(KioskApp.LOG, "Dashboard list JSON rejected", e);
        }
        return items;
    }

    /** 读完整响应。 */
    private static String read(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) out.write(buffer, 0, count);
        return out.toString(StandardCharsets.UTF_8);
    }
}
