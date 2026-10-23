package cn.local.tabletkiosk;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 在本机局域网里查找监听 8123 的 Home Assistant。 */
public final class HaDiscovery {
    private static final int PORT = 8123;
    private static final int CONNECT_MS = 280;
    private static final int HTTP_MS = 700;

    private HaDiscovery() {
    }

    /** 扫描本机 IPv4 所在的私有网段，返回可写入首页的地址。 */
    public static List<String> scan(String localIpv4) {
        String prefix = subnetPrefix(localIpv4);
        if (prefix == null) return List.of();
        List<String> found = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(32);
        for (int host = 1; host <= 254; host++) {
            String ip = prefix + host;
            if (ip.equals(localIpv4)) continue;
            pool.execute(() -> {
                if (acceptsHa(ip)) found.add(homeUrl(ip));
            });
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(12, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        List<String> sorted = new ArrayList<>(found);
        Collections.sort(sorted);
        Log.i(KioskApp.LOG, "HA discovery found " + sorted.size());
        return sorted;
    }

    /** 私有 IPv4 的前三段，带结尾点。公网和链路本地地址返回 null。 */
    static String subnetPrefix(String ip) {
        if (!privateIpv4(ip)) return null;
        int last = ip.lastIndexOf('.');
        if (last <= 0) return null;
        return ip.substring(0, last + 1);
    }

    /** 只接受 10/8、172.16/12 和 192.168/16。 */
    static boolean privateIpv4(String ip) {
        if (ip == null) return false;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        int[] n = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                n[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (n[i] < 0 || n[i] > 255) return false;
        }
        if (n[0] == 10) return true;
        if (n[0] == 192 && n[1] == 168) return true;
        return n[0] == 172 && n[1] >= 16 && n[1] <= 31;
    }

    /** 扫描结果写成首页地址。 */
    static String homeUrl(String ip) {
        return "http://" + ip + ":" + PORT + "/";
    }

    /** 端口能连上，并且响应像 Home Assistant。 */
    private static boolean acceptsHa(String ip) {
        if (!portOpen(ip)) return false;
        return looksLikeHa(readApi(ip));
    }

    /** 短超时探测 8123，避免把整个网段拖住。 */
    private static boolean portOpen(String ip) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName(ip), PORT), CONNECT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 读取 /api/ 或 /manifest.json 的正文。失败时返回空字符串。 */
    private static String readApi(String ip) {
        String api = request("http://" + ip + ":" + PORT + "/api/");
        if (looksLikeHa(api)) return api;
        return request("http://" + ip + ":" + PORT + "/manifest.json");
    }

    /** 确认响应来自 Home Assistant，而不是碰巧占用了 8123 的其他程序。 */
    static boolean looksLikeHa(String body) {
        if (body == null) return false;
        String text = body.toLowerCase(java.util.Locale.ROOT);
        return text.contains("api running") || text.contains("home assistant");
    }

    /** 发起一次短 HTTP 读取，不跟随到外网。 */
    private static String request(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(HTTP_MS);
            connection.setReadTimeout(HTTP_MS);
            int status = connection.getResponseCode();
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) return "";
            return read(input);
        } catch (Exception e) {
            return "";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** 读完有限长度的响应，避免异常页面占满内存。 */
    private static String read(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        int total = 0;
        while (total < 4096 && (count = input.read(buffer)) >= 0) {
            out.write(buffer, 0, count);
            total += count;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
