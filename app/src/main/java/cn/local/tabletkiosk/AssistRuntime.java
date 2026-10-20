package cn.local.tabletkiosk;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** 常驻听唤醒词，命中或点按后把这一句交给 Home Assistant Assist。 */
final class AssistRuntime {
    static final String IDLE = "idle";
    static final String LISTENING = "listening";
    static final String THINKING = "thinking";
    static final String SPEAKING = "speaking";

    /** 界面上的状态。回调都在主线程。 */
    interface Listener {
        /** 更新待命、正在听、正在想、正在说，以及要显示的文字。 */
        void onAssist(String state, String detail);

        /** 点亮屏幕。 */
        void onAssistWake();

        /** 用令牌播放回答，播完调用 finished。 */
        void onAssistPlayback(String url, Runnable finished);

        /** 对话期间不要进入待机和夜间熄屏。 */
        void onAssistHold(boolean hold);
    }

    private static volatile AssistRuntime instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();
    private final Object lock = new Object();
    private final ArrayDeque<byte[]> pendingAudio = new ArrayDeque<>();
    private final WakeWordGate gate = new WakeWordGate();
    private final UtteranceEnd utterance = new UtteranceEnd();

    private Listener listener;
    private OpenWakeWordEngine engine;
    private AudioRecord recorder;
    private Thread audioThread;
    private WebSocket socket;
    private boolean running;
    private boolean forwarding;
    private boolean paused;
    private int handlerId = -1;
    private int messageId = 1;
    private String state = IDLE;
    private String detail = "";
    private String conversationId = "";
    private long conversationUntil;
    private int pendingBytes;

    private AssistRuntime(Context context) {
        app = context.getApplicationContext();
    }

    /** 前台服务创建时启动。重复调用不会再开一条采集。 */
    static AssistRuntime start(Context context) {
        AssistRuntime runtime = instance;
        if (runtime == null) {
            runtime = new AssistRuntime(context);
            instance = runtime;
        }
        runtime.apply();
        return runtime;
    }

    /** 界面注册或取消监听。 */
    void setListener(Listener next) {
        listener = next;
        Listener current = next;
        if (current != null) {
            String shownState = state;
            String shownDetail = detail;
            main.post(() -> current.onAssist(shownState, shownDetail));
        }
    }

    /** 配置或麦克风权限变化后，打开或停掉采集。 */
    void apply() {
        KioskConfig config = KioskHub.get().config();
        boolean enabled = config.assistEnabled
                && app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (!enabled) {
            stopCapture();
            return;
        }
        startCapture();
    }

    /** 点一下麦克风：没在听就开始，正在听就结束这一句。 */
    void tap() {
        synchronized (lock) {
            if (LISTENING.equals(state)) {
                finishUtteranceLocked();
                return;
            }
            if (!IDLE.equals(state)) return;
        }
        begin(false);
    }

    /** 停掉采集和当前连接。 */
    void shutdown() {
        stopCapture();
        setListener(null);
    }

    /** 打开麦克风线程。已经在采集时不重复打开。 */
    private void startCapture() {
        synchronized (lock) {
            if (running) return;
            running = true;
        }
        if (engine == null) engine = new OpenWakeWordEngine(app);
        int min = AudioRecord.getMinBufferSize(AssistProtocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min, 2560 * 2);
        AudioRecord record;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, AssistProtocol.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
        } catch (SecurityException e) {
            Log.w(KioskApp.LOG, "Microphone permission missing", e);
            synchronized (lock) {
                running = false;
            }
            return;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            Log.w(KioskApp.LOG, "Microphone failed to initialize");
            synchronized (lock) {
                running = false;
            }
            return;
        }
        recorder = record;
        record.startRecording();
        audioThread = new Thread(this::captureLoop, "kiosk-assist");
        audioThread.start();
        Log.i(KioskApp.LOG, "Assist microphone started");
    }

    /** 停止采集并结束当前对话连接。 */
    private void stopCapture() {
        Thread thread;
        AudioRecord record;
        synchronized (lock) {
            running = false;
            forwarding = false;
            thread = audioThread;
            record = recorder;
            audioThread = null;
            recorder = null;
            closeSocketLocked();
            state = IDLE;
        }
        if (record != null) {
            try {
                record.stop();
            } catch (IllegalStateException e) {
                Log.w(KioskApp.LOG, "Microphone stop failed", e);
            }
            record.release();
        }
        if (thread != null) thread.interrupt();
        publish(IDLE, "");
        hold(false);
    }

    /** 采集循环：平时只在本机打分，听写阶段才上传。 */
    private void captureLoop() {
        byte[] buf = new byte[2560];
        while (true) {
            AudioRecord record;
            boolean active;
            synchronized (lock) {
                active = running;
                record = recorder;
            }
            if (!active || record == null) return;
            int n = record.read(buf, 0, buf.length);
            if (n <= 0) continue;
            byte[] copy = new byte[n];
            System.arraycopy(buf, 0, copy, 0, n);
            float score = engine == null ? -1f : engine.push(copy, n);
            long now = SystemClock.elapsedRealtime();
            KioskConfig config = KioskHub.get().config();
            boolean wake;
            boolean send;
            synchronized (lock) {
                boolean busy = paused || !IDLE.equals(state);
                wake = gate.hear(score, WakeWordGate.threshold(config.wakeSensitivity), now, busy);
                send = forwarding;
                if (send) queueAudioLocked(copy);
            }
            if (wake) begin(true);
            if (send && utterance.finished(UtteranceEnd.rms(copy, n), now)) {
                synchronized (lock) {
                    finishUtteranceLocked();
                }
            }
        }
    }

    /** 开始一轮。fromWake 为真时带上唤醒词，避免多台设备一起回答。 */
    private void begin(boolean fromWake) {
        KioskHub hub = KioskHub.get();
        String token = hub.store().haToken();
        String origin = HaDashboards.originOf(hub.config().homeUrl);
        if (token.isEmpty() || origin == null) {
            publish(IDLE, "token");
            return;
        }
        String kept;
        synchronized (lock) {
            if (!IDLE.equals(state)) return;
            long now = SystemClock.elapsedRealtime();
            kept = now <= conversationUntil ? conversationId : "";
            if (kept.isEmpty()) conversationId = "";
            state = LISTENING;
            forwarding = false;
            handlerId = -1;
            pendingAudio.clear();
            pendingBytes = 0;
            utterance.reset();
            paused = true;
            closeSocketLocked();
        }
        publish(LISTENING, "");
        hold(true);
        main.post(() -> {
            Listener current = listener;
            if (current != null) current.onAssistWake();
        });
        String scheme = origin.startsWith("https://") ? "wss://" : "ws://";
        String url = scheme + origin.substring(origin.indexOf("://") + 3) + "/api/websocket";
        Request request = new Request.Builder().url(url).build();
        String phrase = fromWake ? AssistProtocol.WAKE_PHRASE : "";
        String conversation = kept;
        socket = http.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                onText(webSocket, text, phrase, conversation);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.w(KioskApp.LOG, "Assist websocket failed", t);
                fail("unreachable");
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
            }
        });
    }

    /** 处理服务端文本。令牌和音频正文不写日志。 */
    private void onText(WebSocket webSocket, String text, String phrase, String conversation) {
        AssistProtocol.Message message = AssistProtocol.parse(text);
        if ("auth_required".equals(message.kind)) {
            webSocket.send(AssistProtocol.authMessage(KioskHub.get().store().haToken()));
            return;
        }
        if ("auth_invalid".equals(message.kind)) {
            fail("unauthorized");
            return;
        }
        if ("auth_ok".equals(message.kind)) {
            int id;
            synchronized (lock) {
                id = messageId++;
            }
            webSocket.send(AssistProtocol.runMessage(id, conversation, phrase));
            return;
        }
        if (!"event".equals(message.kind)) return;
        if ("run-start".equals(message.eventType)) {
            synchronized (lock) {
                if (message.handlerId >= 0) handlerId = message.handlerId;
                if (!message.conversationId.isEmpty()) conversationId = message.conversationId;
            }
            return;
        }
        if ("stt-start".equals(message.eventType)) {
            synchronized (lock) {
                forwarding = true;
                utterance.reset();
                flushPendingLocked(webSocket);
            }
            return;
        }
        if ("stt-end".equals(message.eventType)) {
            synchronized (lock) {
                forwarding = false;
            }
            publish(THINKING, message.transcript);
            return;
        }
        if ("intent-end".equals(message.eventType)) {
            publish(THINKING, message.reply.isEmpty() ? detail : message.reply);
            return;
        }
        if ("tts-end".equals(message.eventType)) {
            String origin = HaDashboards.originOf(KioskHub.get().config().homeUrl);
            String url = AssistProtocol.absoluteUrl(origin, message.ttsUrl);
            publish(SPEAKING, detail);
            main.post(() -> {
                Listener current = listener;
                if (current == null || url.isEmpty()) {
                    finishSpeaking();
                    return;
                }
                current.onAssistPlayback(url, this::finishSpeaking);
            });
            return;
        }
        if ("error".equals(message.eventType)) {
            Log.w(KioskApp.LOG, "Assist pipeline error code received");
            fail(message.error.isEmpty() ? "error" : message.error);
            return;
        }
        if ("run-end".equals(message.eventType)) {
            synchronized (lock) {
                conversationUntil = SystemClock.elapsedRealtime() + AssistProtocol.CONVERSATION_HOLD_MS;
                closeSocketLocked();
            }
        }
    }

    /** 回答播放结束。再等半秒才恢复唤醒，避免听见自己的声音。 */
    private void finishSpeaking() {
        synchronized (lock) {
            forwarding = false;
            state = IDLE;
            gate.pause(SystemClock.elapsedRealtime() + 500);
            paused = false;
            closeSocketLocked();
        }
        publish(IDLE, "");
        hold(false);
    }

    /** 结束上传并进入思考。 */
    private void finishUtteranceLocked() {
        if (!forwarding) return;
        forwarding = false;
        state = THINKING;
        WebSocket webSocket = socket;
        int handler = handlerId;
        if (webSocket != null && handler >= 0) webSocket.send(ByteString.of(AssistProtocol.audioEnd(handler)));
        publish(THINKING, detail);
    }

    /** 听写开始前先攒最多 1 秒，开始后立刻补发出去。 */
    private void queueAudioLocked(byte[] pcm) {
        if (handlerId < 0 || socket == null) {
            pendingAudio.add(pcm);
            pendingBytes += pcm.length;
            while (pendingBytes > AssistProtocol.SAMPLE_RATE * 2 && !pendingAudio.isEmpty()) {
                pendingBytes -= pendingAudio.removeFirst().length;
            }
            return;
        }
        socket.send(ByteString.of(AssistProtocol.audioChunk(handlerId, pcm, pcm.length)));
    }

    /** 把开始听写前攒下的音频按顺序发出去。 */
    private void flushPendingLocked(WebSocket webSocket) {
        if (handlerId < 0) return;
        while (!pendingAudio.isEmpty()) {
            byte[] pcm = pendingAudio.removeFirst();
            webSocket.send(ByteString.of(AssistProtocol.audioChunk(handlerId, pcm, pcm.length)));
        }
        pendingBytes = 0;
    }

    /** 失败时回到待命，并在画面上给出简短原因。 */
    private void fail(String reason) {
        synchronized (lock) {
            forwarding = false;
            state = IDLE;
            paused = false;
            closeSocketLocked();
        }
        publish(IDLE, reason);
        hold(false);
        Log.w(KioskApp.LOG, "Assist stopped: " + reason);
    }

    /** 关闭当前套接字。调用方需要持有 lock。 */
    private void closeSocketLocked() {
        WebSocket webSocket = socket;
        socket = null;
        if (webSocket != null) webSocket.close(1000, null);
    }

    /** 把状态交给界面。 */
    private void publish(String next, String text) {
        synchronized (lock) {
            if (LISTENING.equals(next) || THINKING.equals(next) || SPEAKING.equals(next) || IDLE.equals(next)) {
                state = next;
            }
            detail = text == null ? "" : text;
        }
        String shown = text == null ? "" : text;
        main.post(() -> {
            Listener current = listener;
            if (current != null) current.onAssist(next, shown);
        });
    }

    /** 通知界面是否推迟待机。 */
    private void hold(boolean hold) {
        main.post(() -> {
            Listener current = listener;
            if (current != null) current.onAssistHold(hold);
        });
    }
}
