package cn.local.tabletkiosk;

import android.util.Log;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** 局域网管理接口：只接受局域网来源，每个请求都要带管理密码。 */
final class LanServer {
    private static final int SOCKET_TIMEOUT_MS = 5000;
    private static final long AUTH_FAILURE_DELAY_MS = 500;
    private static final String OK = "{\"ok\":true}";

    private final KioskHub hub;
    private KioskConfig applied;
    private ServerSocket server;
    private ExecutorService workers;
    private volatile String password = "";

    /** 绑定状态中心。 */
    LanServer(KioskHub hub) {
        this.hub = hub;
    }

    /** 应用新配置；端口、开关或密码变化时重启监听。 */
    synchronized void apply(KioskConfig next) {
        if (applied != null && next.sameLanServer(applied)) return;
        applied = next;
        stop();
        if (!next.lanEnabled) return;
        if (next.lanPassword.length() < ConfigForm.MIN_LAN_PASSWORD_LENGTH) {
            Log.w(KioskApp.LOG, "LAN server not started: password too short");
            return;
        }
        start(next.lanPort, next.lanPassword);
    }

    /** 在所有网卡上监听指定端口，来源地址在处理请求时过滤。 */
    private void start(int port, String secret) {
        password = secret;
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            ExecutorService pool = Executors.newFixedThreadPool(2);
            server = socket;
            workers = pool;
            Thread accept = new Thread(() -> acceptLoop(socket, pool), "kiosk-lan-accept");
            accept.setDaemon(true);
            accept.start();
            Log.i(KioskApp.LOG, "LAN server listening on port " + port);
        } catch (IOException e) {
            Log.e(KioskApp.LOG, "LAN server failed to bind port " + port, e);
        }
    }

    /** 停止监听并结束处理线程。 */
    synchronized void stop() {
        if (server != null) {
            try {
                server.close();
            } catch (IOException e) {
                Log.w(KioskApp.LOG, "LAN server close failed", e);
            }
            server = null;
        }
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
    }

    /** 接收连接并交给处理线程。 */
    private void acceptLoop(ServerSocket socket, ExecutorService pool) {
        while (!socket.isClosed()) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                if (!socket.isClosed()) Log.e(KioskApp.LOG, "LAN accept failed", e);
                return;
            }
            try {
                pool.execute(() -> handle(client));
            } catch (RejectedExecutionException e) {
                closeQuietly(client);
                return;
            }
        }
    }

    /** 处理一条请求：先查来源，再读请求、校验密码并执行。 */
    private void handle(Socket client) {
        String remote = String.valueOf(client.getInetAddress());
        try (Socket s = client) {
            s.setSoTimeout(SOCKET_TIMEOUT_MS);
            OutputStream out = s.getOutputStream();
            if (!LanHttp.isLanAddress(s.getInetAddress())) {
                Log.w(KioskApp.LOG, "LAN request rejected from non-local address " + remote);
                out.write(LanHttp.response(403, "{\"ok\":false,\"error\":\"lan only\"}"));
                return;
            }
            LanHttp.Request req;
            try {
                req = LanHttp.read(s.getInputStream());
            } catch (LanHttp.BadRequest e) {
                Log.w(KioskApp.LOG, "LAN bad request from " + remote + ": " + e.getMessage());
                out.write(LanHttp.response(400, "{\"ok\":false,\"error\":\"bad request\"}"));
                return;
            }
            if (!LanHttp.authorized(req.headers, password)) {
                Log.w(KioskApp.LOG, "LAN unauthorized " + req.method + " " + req.path + " from " + remote);
                // 失败后稍作等待，降低暴力猜测速度
                Thread.sleep(AUTH_FAILURE_DELAY_MS);
                out.write(LanHttp.response(401, "{\"ok\":false,\"error\":\"unauthorized\"}"));
                return;
            }
            Reply reply = route(req);
            out.write(LanHttp.response(reply.status, reply.json));
            out.flush();
            Log.i(KioskApp.LOG, "LAN " + req.method + " " + req.path + " from " + remote + " -> " + reply.status);
            if (reply.after != null) reply.after.run();
        } catch (IOException e) {
            Log.w(KioskApp.LOG, "LAN connection failed from " + remote + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 按路径执行命令。 */
    private Reply route(LanHttp.Request req) {
        switch (req.path) {
            case "/status":
                if (!"GET".equals(req.method)) return methodNotAllowed();
                return new Reply(200, StatusJson.of(hub.status(), hub.config(), true), null);
            case "/reload":
                if (!"POST".equals(req.method)) return methodNotAllowed();
                hub.requestReload();
                return new Reply(200, OK, null);
            case "/wake":
                if (!"POST".equals(req.method)) return methodNotAllowed();
                hub.requestWake();
                return new Reply(200, OK, null);
            case "/brightness":
                if (!"POST".equals(req.method)) return methodNotAllowed();
                Integer value = LanHttp.brightnessFrom(req);
                if (value == null) return new Reply(400, "{\"ok\":false,\"error\":\"brightness must be 0-100\"}", null);
                hub.requestWatchBrightness(value);
                return new Reply(200, "{\"ok\":true,\"watch_brightness\":" + value + "}", null);
            case "/reboot":
                if (!"POST".equals(req.method)) return methodNotAllowed();
                if (!hub.status().deviceOwner) {
                    return new Reply(409, "{\"ok\":false,\"error\":\"device owner required\"}", null);
                }
                // 先把响应发出去，再重启
                return new Reply(200, OK, hub::requestReboot);
            default:
                return new Reply(404, "{\"ok\":false,\"error\":\"not found\"}", null);
        }
    }

    /** 方法不对时的响应。 */
    private static Reply methodNotAllowed() {
        return new Reply(405, "{\"ok\":false,\"error\":\"method not allowed\"}", null);
    }

    /** 关闭连接并忽略异常。 */
    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            Log.w(KioskApp.LOG, "LAN socket close failed", e);
        }
    }

    /** 路由结果；after 在响应发出后执行。 */
    private static final class Reply {
        final int status;
        final String json;
        final Runnable after;

        /** 保存状态码、内容和后续动作。 */
        Reply(int status, String json, Runnable after) {
            this.status = status;
            this.json = json;
            this.after = after;
        }
    }
}
