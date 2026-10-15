package cn.local.tabletkiosk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 局域网接口的最小 HTTP 解析、密码校验和来源地址判断；只用 Java 标准库，便于单测。 */
final class LanHttp {
    static final int MAX_HEAD = 8192;
    static final int MAX_BODY = 1024;
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private LanHttp() {
    }

    /** 请求格式错误或超长。 */
    static final class BadRequest extends IOException {
        /** 携带英文原因。 */
        BadRequest(String message) {
            super(message);
        }
    }

    /** 解析后的请求；请求头名称统一小写。 */
    static final class Request {
        final String method;
        final String path;
        final Map<String, String> query;
        final Map<String, String> headers;
        final String body;

        /** 保存请求内容。 */
        Request(String method, String path, Map<String, String> query, Map<String, String> headers, String body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
        }
    }

    /** 读取一条请求，请求头不超过 8KB，正文不超过 1KB。 */
    static Request read(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        // 逐字节读到空行为止
        while (matched < 4) {
            int b = in.read();
            if (b < 0) throw new BadRequest("Connection closed before end of headers");
            head.write(b);
            if (head.size() > MAX_HEAD) throw new BadRequest("Request headers too large");
            if ((matched % 2 == 0 && b == '\r') || (matched % 2 == 1 && b == '\n')) matched++;
            else matched = b == '\r' ? 1 : 0;
        }
        String[] lines = head.toString(StandardCharsets.ISO_8859_1.name()).split("\r\n");
        String[] start = lines[0].split(" ");
        if (start.length != 3 || !start[2].startsWith("HTTP/1.")) throw new BadRequest("Malformed request line");
        String method = start[0].toUpperCase(Locale.ROOT);
        String target = start[1];
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }

        int length = 0;
        String declared = headers.get("content-length");
        if (declared != null) {
            try {
                length = Integer.parseInt(declared);
            } catch (NumberFormatException e) {
                throw new BadRequest("Invalid Content-Length");
            }
            if (length < 0 || length > MAX_BODY) throw new BadRequest("Request body too large");
        }
        byte[] body = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(body, read, length - read);
            if (n < 0) throw new BadRequest("Connection closed before end of body");
            read += n;
        }

        int q = target.indexOf('?');
        String path = q >= 0 ? target.substring(0, q) : target;
        Map<String, String> query = q >= 0 ? parseQuery(target.substring(q + 1)) : new HashMap<>();
        return new Request(method, path, query, headers, new String(body, StandardCharsets.UTF_8));
    }

    /** 解析查询字符串。 */
    static Map<String, String> parseQuery(String raw) throws BadRequest {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        try {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                String key = URLDecoder.decode(eq >= 0 ? pair.substring(0, eq) : pair, "UTF-8");
                String value = eq >= 0 ? URLDecoder.decode(pair.substring(eq + 1), "UTF-8") : "";
                out.put(key, value);
            }
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            throw new BadRequest("Malformed query string");
        }
        return out;
    }

    /**
     * 校验管理密码：支持 Authorization: Bearer 或 X-Kiosk-Password 请求头。
     * 不接受查询参数，避免密码进入访问记录；比较时长度和耗时与内容无关。
     */
    static boolean authorized(Map<String, String> headers, String password) {
        if (password == null || password.isEmpty()) return false;
        String supplied = null;
        String auth = headers.get("authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) supplied = auth.substring(7).trim();
        if (supplied == null) supplied = headers.get("x-kiosk-password");
        if (supplied == null) return false;
        return MessageDigest.isEqual(sha256(supplied), sha256(password));
    }

    /** 只接受本机、局域网私有地址、链路本地和 IPv6 唯一本地地址。 */
    static boolean isLanAddress(InetAddress address) {
        if (address == null) return false;
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) return true;
        return address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC;
    }

    /** 从查询参数 value 或正文中的第一个数字取亮度，接受 70、value=70 或 {"brightness":70}。 */
    static Integer brightnessFrom(Request request) {
        String value = request.query.get("value");
        if (value == null) {
            Matcher m = NUMBER.matcher(request.body);
            if (m.find()) value = m.group();
        }
        return MqttCommands.parseBrightness(value);
    }

    /** 生成完整的 JSON 响应字节。 */
    static byte[] response(int status, String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[headBytes.length + body.length];
        System.arraycopy(headBytes, 0, out, 0, headBytes.length);
        System.arraycopy(body, 0, out, headBytes.length, body.length);
        return out;
    }

    /** 状态码对应的英文短语。 */
    static String reason(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 409:
                return "Conflict";
            default:
                return "Internal Server Error";
        }
    }

    /** 计算 SHA-256，让比较结果与密码长度无关。 */
    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
