package cn.local.tabletkiosk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import java.util.concurrent.atomic.AtomicReference;

/** 仪表盘地址只取首页的协议和主机，再接 url_path。 */
public class HaDashboardsTest {
    /** 带路径的首页不会把路径再拼进仪表盘地址。 */
    @Test
    public void originDropsPagePath() {
        assertEquals("https://ha.example:8123", HaDashboards.originOf("https://ha.example:8123/home-portrait/home"));
        assertNull(HaDashboards.originOf("file:///local"));
        assertEquals("https://ha.example:8123/lovelace-tablet",
                HaDashboards.pageUrl("https://ha.example:8123", "lovelace-tablet"));
    }
    /** 正确令牌通过 WebSocket 鉴权后读取列表，保留中文名称和页面地址。 */
    @Test
    public void listsDashboardsAfterAuthentication() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AtomicReference<String> command = new AtomicReference<>();
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                /** 模拟 HA 发起鉴权。 */
                @Override
                public void onOpen(WebSocket socket, Response response) {
                    socket.send("{\"type\":\"auth_required\"}");
                }

                /** 先验证客户端令牌，再返回仪表盘列表。 */
                @Override
                public void onMessage(WebSocket socket, String text) {
                    if (text.contains("\"type\":\"auth\"")) {
                        command.set(text);
                        socket.send("{\"type\":\"auth_ok\"}");
                    } else {
                        command.set(command.get() + text);
                        socket.send("{\"id\":1,\"type\":\"result\",\"success\":true,"
                                + "\"result\":[{\"title\":\"居家大屏\",\"url_path\":\"home-evening\"}]}");
                    }
                }
            }));
            String origin = server.url("/").toString().replaceAll("/$", "");
            HaDashboards.Outcome outcome = HaDashboards.load(origin + "/existing/home", " fresh-token ", new OkHttpClient());
            assertNull(outcome.error);
            assertEquals(1, outcome.items.size());
            assertEquals("居家大屏", outcome.items.get(0).title);
            assertEquals(origin + "/home-evening", outcome.items.get(0).url);
            org.junit.Assert.assertTrue(command.get().contains("\"access_token\":\"fresh-token\""));
            org.junit.Assert.assertTrue(command.get().contains("lovelace/dashboards/list"));
            assertEquals("/api/websocket", server.takeRequest().getPath());
        }
    }

    /** 明确的鉴权拒绝才报告令牌错误，命令权限错误单独区分。 */
    @Test
    public void distinguishesAuthenticationAndPermissionFailures() throws Exception {
        assertEquals("unauthorized", responseError("{\"type\":\"auth_invalid\"}"));
        assertEquals("forbidden", responseError("{\"id\":1,\"type\":\"result\",\"success\":false,"
                + "\"error\":{\"code\":\"unauthorized\"}}"));
        assertEquals("bad_response", responseError("{\"id\":1,\"type\":\"result\",\"success\":true,\"result\":{}}"));
    }

    /** 用本地服务发送指定响应，不访问真实 HA 或设备。 */
    private String responseError(String response) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
                /** 建立连接后返回待验证的错误或异常数据。 */
                @Override
                public void onOpen(WebSocket socket, Response handshake) {
                    socket.send(response);
                }
            }));
            return HaDashboards.load(server.url("/").toString(), "test-token", new OkHttpClient()).error;
        }
    }

    /** 空令牌与错误地址在联网之前给出各自的提示。 */
    @Test
    public void rejectsMissingTokenAndInvalidAddress() {
        assertEquals("invalid_url", HaDashboards.load("file:///bad", "token").error);
        assertEquals("unauthorized", HaDashboards.load("https://ha.example", " ").error);
    }
}
