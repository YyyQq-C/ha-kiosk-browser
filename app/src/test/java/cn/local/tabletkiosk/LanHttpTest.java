package cn.local.tabletkiosk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/** 局域网接口：请求解析、密码校验、来源地址和亮度参数。 */
public class LanHttpTest {
    private static final String SECRET = "test-password";

    /** 把文本作为请求读入。 */
    private static LanHttp.Request read(String raw) throws IOException {
        return LanHttp.read(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    /** 带正文和查询参数的请求。 */
    @Test
    public void parsesRequest() throws IOException {
        LanHttp.Request r = read("POST /brightness?value=40&x=%E4%B8%AD HTTP/1.1\r\n"
                + "Host: 192.168.1.30:2323\r\nX-Kiosk-Password: abc\r\nContent-Length: 4\r\n\r\nbody");
        assertEquals("POST", r.method);
        assertEquals("/brightness", r.path);
        assertEquals("40", r.query.get("value"));
        assertEquals("中", r.query.get("x"));
        assertEquals("abc", r.headers.get("x-kiosk-password"));
        assertEquals("body", r.body);
    }

    /** 格式错误或超长时拒绝。 */
    @Test
    public void rejectsMalformedRequests() {
        assertBad("GARBAGE\r\n\r\n");
        assertBad("GET /status HTTP/1.1\r\nHost: x\r\n");
        assertBad("POST /x HTTP/1.1\r\nContent-Length: 99999\r\n\r\n");
        assertBad("POST /x HTTP/1.1\r\nContent-Length: nope\r\n\r\n");
        assertBad("POST /x HTTP/1.1\r\nContent-Length: 10\r\n\r\nshort");
        StringBuilder huge = new StringBuilder("GET / HTTP/1.1\r\n");
        while (huge.length() <= LanHttp.MAX_HEAD) huge.append("X-Pad: 0123456789\r\n");
        assertBad(huge.append("\r\n").toString());
    }

    /** 断言请求被判为格式错误。 */
    private static void assertBad(String raw) {
        try {
            read(raw);
        } catch (LanHttp.BadRequest expected) {
            return;
        } catch (IOException e) {
            throw new AssertionError("unexpected " + e);
        }
        throw new AssertionError("accepted: " + raw);
    }

    /** 两种请求头都可以携带密码。 */
    @Test
    public void acceptsPasswordHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("authorization", "Bearer " + SECRET);
        assertTrue(LanHttp.authorized(h, SECRET));
        h.put("authorization", "bearer " + SECRET);
        assertTrue(LanHttp.authorized(h, SECRET));
        h.clear();
        h.put("x-kiosk-password", SECRET);
        assertTrue(LanHttp.authorized(h, SECRET));
    }

    /** 缺少密码、密码错误或未设置密码时拒绝。 */
    @Test
    public void rejectsWrongPassword() {
        Map<String, String> h = new HashMap<>();
        assertFalse(LanHttp.authorized(h, SECRET));
        h.put("authorization", "Bearer wrong-password");
        assertFalse(LanHttp.authorized(h, SECRET));
        h.put("authorization", "Basic " + SECRET);
        assertFalse(LanHttp.authorized(h, SECRET));
        h.clear();
        h.put("x-kiosk-password", SECRET + "x");
        assertFalse(LanHttp.authorized(h, SECRET));
        h.put("x-kiosk-password", "");
        assertFalse(LanHttp.authorized(h, ""));
    }

    /** 只接受局域网来源。 */
    @Test
    public void lanAddressFilter() throws IOException {
        assertTrue(LanHttp.isLanAddress(InetAddress.getByName("127.0.0.1")));
        assertTrue(LanHttp.isLanAddress(InetAddress.getByName("192.168.1.16")));
        assertTrue(LanHttp.isLanAddress(InetAddress.getByName("10.0.0.5")));
        assertTrue(LanHttp.isLanAddress(InetAddress.getByName("172.20.1.1")));
        assertTrue(LanHttp.isLanAddress(InetAddress.getByName("169.254.3.4")));
        assertTrue(LanHttp.isLanAddress(InetAddress.getByName("fe80::1")));
        assertTrue(LanHttp.isLanAddress(InetAddress.getByName("fd12:3456::1")));
        assertTrue(LanHttp.isLanAddress(InetAddress.getByName("::1")));
        assertFalse(LanHttp.isLanAddress(InetAddress.getByName("8.8.8.8")));
        assertFalse(LanHttp.isLanAddress(InetAddress.getByName("100.121.168.87")));
        assertFalse(LanHttp.isLanAddress(InetAddress.getByName("2001:4860::8888")));
        assertFalse(LanHttp.isLanAddress(null));
    }

    /** 亮度可从查询参数、纯数字正文或 JSON 正文读取。 */
    @Test
    public void brightnessSources() throws IOException {
        assertEquals(Integer.valueOf(40), LanHttp.brightnessFrom(read("POST /brightness?value=40 HTTP/1.1\r\n\r\n")));
        assertEquals(Integer.valueOf(65), LanHttp.brightnessFrom(
                read("POST /brightness HTTP/1.1\r\nContent-Length: 2\r\n\r\n65")));
        assertEquals(Integer.valueOf(30), LanHttp.brightnessFrom(
                read("POST /brightness HTTP/1.1\r\nContent-Length: 17\r\n\r\n{\"brightness\":30}")));
        assertNull(LanHttp.brightnessFrom(read("POST /brightness HTTP/1.1\r\n\r\n")));
        assertNull(LanHttp.brightnessFrom(read("POST /brightness?value=150 HTTP/1.1\r\n\r\n")));
    }

    /** 响应包含状态行、长度和 JSON 正文。 */
    @Test
    public void responseFormat() {
        String text = new String(LanHttp.response(401, "{\"ok\":false}"), StandardCharsets.UTF_8);
        assertTrue(text.startsWith("HTTP/1.1 401 Unauthorized\r\n"));
        assertTrue(text.contains("Content-Length: 12\r\n"));
        assertTrue(text.contains("Connection: close\r\n"));
        assertTrue(text.endsWith("\r\n\r\n{\"ok\":false}"));
        String chinese = new String(LanHttp.response(200, "{\"a\":\"中\"}"), StandardCharsets.UTF_8);
        assertTrue(chinese.contains("Content-Length: 11\r\n"));
    }
}
