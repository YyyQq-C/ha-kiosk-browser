package cn.local.tabletkiosk;

import android.util.Log;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
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

    /** 读取结果，区分鉴权、权限、地址、网络及协议错误。 */
    public static final class Outcome {
        public final List<Item> items;
        public final String error;

        /** 保存列表和可展示的错误类别。 */
        private Outcome(List<Item> items, String error) {
            this.items = items;
            this.error = error;
        }
    }

    private HaDashboards() {
    }

    /** 在后台等待 WebSocket 鉴权和仪表盘列表，始终释放连接。 */
    public static Outcome load(String homeUrl, String token) {
        return load(homeUrl, token, new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build());
    }

    /** 使用指定客户端读取列表，测试可复用本地 WebSocket 服务。 */
    static Outcome load(String homeUrl, String token, OkHttpClient client) {
        String origin = originOf(homeUrl);
        if (origin == null) return new Outcome(List.of(), "invalid_url");
        if (token == null || token.trim().isEmpty()) return new Outcome(List.of(), "unauthorized");
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        String url = (origin.startsWith("https://") ? "wss://" : "ws://")
                + origin.substring(origin.indexOf("://") + 3) + "/api/websocket";
        WebSocket socket = client.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            /** 先完成鉴权，再发送 HA 实际支持的仪表盘列表命令。 */
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONObject message = new JSONObject(text);
                    String type = message.optString("type");
                    if ("auth_required".equals(type)) {
                        webSocket.send(AssistProtocol.authMessage(token.trim()));
                    } else if ("auth_invalid".equals(type)) {
                        Log.w(KioskApp.LOG, "Dashboard websocket authentication rejected");
                        result.complete(new Outcome(List.of(), "unauthorized"));
                    } else if ("auth_ok".equals(type)) {
                        webSocket.send("{\"id\":1,\"type\":\"lovelace/dashboards/list\"}");
                    } else if ("result".equals(type) && message.optInt("id") == 1) {
                        if (!message.optBoolean("success")) {
                            JSONObject error = message.optJSONObject("error");
                            String code = error == null ? "" : error.optString("code");
                            Log.w(KioskApp.LOG, "Dashboard websocket command rejected");
                            result.complete(new Outcome(List.of(), "unauthorized".equals(code) ? "forbidden" : "bad_response"));
                        } else {
                            JSONArray dashboards = message.getJSONArray("result");
                            result.complete(new Outcome(parse(origin, dashboards.toString()), null));
                        }
                    }
                } catch (Exception e) {
                    Log.w(KioskApp.LOG, "Dashboard websocket response rejected", e);
                    result.complete(new Outcome(List.of(), "bad_response"));
                }
            }

            /** 网络和握手错误单独报告，不把它们误判成令牌错误。 */
            @Override
            public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                Log.w(KioskApp.LOG, "Dashboard websocket connection failed", error);
                result.complete(new Outcome(List.of(), "unreachable"));
            }

            /** 服务端提前关闭时结束等待。 */
            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
                result.complete(new Outcome(List.of(), "unreachable"));
            }
        });
        try {
            return result.get(12, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(KioskApp.LOG, "Dashboard websocket wait interrupted", e);
            return new Outcome(List.of(), "unreachable");
        } catch (Exception e) {
            Log.w(KioskApp.LOG, "Dashboard websocket wait failed", e);
            return new Outcome(List.of(), "unreachable");
        } finally {
            socket.cancel();
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
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

}
