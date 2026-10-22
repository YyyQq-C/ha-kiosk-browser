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

        /** 本轮正在听时推送本机录音幅度，供界面声波使用。 */
        void onAssistLevel(float level);

        /** 点亮屏幕。 */
        void onAssistWake();

        /** 用令牌播放回答，播完调用 finished。 */
        void onAssistPlayback(String url, Runnable finished);

        /** 取消本轮语音时停止回答播放，不关闭常驻唤醒。 */
        void onAssistCancel();

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
    private final AssistSession session = new AssistSession();
    private final SpeechWait speechWait = new SpeechWait();
    private Runnable speechWaitTimeout;

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
    private long lastLevelAt;

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

    /** 点一下开始，再点一下取消；连接、听写、思考和播报阶段均可取消。 */
    void tap() {
        synchronized (lock) {
            if (!IDLE.equals(state)) {
                cancelLocked("tap");
                return;
            }
            begin(false);
        }
    }

    /** 面板关闭按钮只取消活动轮次，不会因重复点击而重新开始。 */
    void cancel() {
        synchronized (lock) {
            if (!IDLE.equals(state)) cancelLocked("tap");
        }
    }

    /** 主线程点击取消时清理本轮，并立即停止播放；调用方持有 lock。 */
    private void cancelLocked(String reason) {
        clearSpeechWaitLocked();
        session.advance();
        forwarding = false;
        paused = false;
        handlerId = -1;
        pendingAudio.clear();
        pendingBytes = 0;
        utterance.reset();
        conversationId = "";
        conversationUntil = 0;
        gate.pause(SystemClock.elapsedRealtime() + 500);
        WebSocket previous = socket;
        socket = null;
        if (previous != null) previous.cancel();
        state = IDLE;
        Listener current = listener;
        if (current != null) current.onAssistCancel();
        publish(IDLE, "");
        hold(false);
        Log.i(KioskApp.LOG, "Assist session cancelled by " + reason);
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
            clearSpeechWaitLocked();
            session.advance();
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
            int loudness = UtteranceEnd.rms(copy, n);
            float score = engine == null ? -1f : engine.push(copy, n);
            long now = SystemClock.elapsedRealtime();
            KioskConfig config = KioskHub.get().config();
            boolean wake;
            boolean send;
            synchronized (lock) {
                boolean busy = paused || !IDLE.equals(state);
                wake = gate.hear(score, WakeWordGate.threshold(config.wakeSensitivity), now, busy);
                send = forwarding;
                if (LISTENING.equals(state)) speechWait.hear(loudness);
                if (send) queueAudioLocked(copy);
                if (LISTENING.equals(state) && now - lastLevelAt >= 80) {
                    lastLevelAt = now;
                    publishLevelLocked(AssistAudioLevel.normalize(loudness));
                }
            }
            if (wake) begin(true);
            if (send && utterance.finished(loudness, now)) {
                synchronized (lock) {
                    finishUtteranceLocked();
                }
            }
        }
    }

    /** 开始一轮。fromWake 为真时带上唤醒词，避免多台设备一起回答。 */
    private void begin(boolean fromWake) {
        synchronized (lock) {
            KioskHub hub = KioskHub.get();
            String token = hub.store().haToken();
            String origin = HaDashboards.originOf(hub.config().homeUrl);
            if (token.isEmpty() || origin == null) {
                publish(IDLE, "token");
                return;
            }
            if (!IDLE.equals(state)) return;
            String kept;
            long generation = session.advance();
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
            armSpeechWaitLocked(generation, hub.config().speechWaitSeconds, now);
            Log.i(KioskApp.LOG, "Assist session started");
            publish(LISTENING, "");
            hold(true);
            main.post(() -> {
                Listener current = listener;
                synchronized (lock) {
                    if (session.accepts(generation) && current != null) current.onAssistWake();
                }
            });
            String scheme = origin.startsWith("https://") ? "wss://" : "ws://";
            String url = scheme + origin.substring(origin.indexOf("://") + 3) + "/api/websocket";
            Request request = new Request.Builder().url(url).build();
            String phrase = fromWake ? AssistProtocol.WAKE_PHRASE : "";
            String conversation = kept;
            socket = http.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    synchronized (lock) {
                        if (session.accepts(generation) && socket == webSocket) {
                            onText(webSocket, text, phrase, conversation);
                        }
                    }
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    synchronized (lock) {
                        if (!session.accepts(generation) || socket != webSocket) return;
                        Log.w(KioskApp.LOG, "Assist websocket failed", t);
                        fail("unreachable");
                    }
                }

                @Override
                public void onClosing(WebSocket webSocket, int code, String reason) {
                    webSocket.close(1000, null);
                }
            });
        }
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
            long generation = session.current();
            main.post(() -> {
                synchronized (lock) {
                    if (!session.accepts(generation)) return;
                    Listener current = listener;
                    if (current == null || url.isEmpty()) {
                        finishSpeaking(generation);
                        return;
                    }
                    current.onAssistPlayback(url, () -> finishSpeaking(generation));
                }
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
    private void finishSpeaking(long generation) {
        synchronized (lock) {
            if (!session.accepts(generation)) return;
            clearSpeechWaitLocked();
            forwarding = false;
            state = IDLE;
            gate.pause(SystemClock.elapsedRealtime() + 500);
            paused = false;
            closeSocketLocked();
            publish(IDLE, "");
            hold(false);
        }
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
            clearSpeechWaitLocked();
            forwarding = false;
            state = IDLE;
            paused = false;
            closeSocketLocked();
        }
        publish(IDLE, reason);
        hold(false);
        Log.w(KioskApp.LOG, "Assist stopped: " + reason);
    }

    /** 从面板开始显示就计时，网络尚未准备好也能按时关闭。调用方持有 lock。 */
    private void armSpeechWaitLocked(long generation, int seconds, long now) {
        clearSpeechWaitLocked();
        speechWait.start(now);
        speechWaitTimeout = () -> {
            synchronized (lock) {
                if (session.accepts(generation) && LISTENING.equals(state)
                        && speechWait.expired(SystemClock.elapsedRealtime(), seconds)) {
                    cancelLocked("no-speech timeout");
                }
            }
        };
        main.postDelayed(speechWaitTimeout, seconds * 1000L);
    }

    /** 移除上一轮的等待任务，避免取消或重开后旧计时器干扰新轮次。 */
    private void clearSpeechWaitLocked() {
        if (speechWaitTimeout != null) main.removeCallbacks(speechWaitTimeout);
        speechWaitTimeout = null;
    }

    /** 关闭当前套接字。调用方需要持有 lock。 */
    private void closeSocketLocked() {
        WebSocket webSocket = socket;
        socket = null;
        if (webSocket != null) webSocket.close(1000, null);
    }

    /** 把状态交给界面。 */
    private void publish(String next, String text) {
        long generation;
        synchronized (lock) {
            if (LISTENING.equals(next) || THINKING.equals(next) || SPEAKING.equals(next) || IDLE.equals(next)) {
                state = next;
            }
            detail = text == null ? "" : text;
            generation = session.current();
        }
        String shown = text == null ? "" : text;
        main.post(() -> {
            synchronized (lock) {
                Listener current = listener;
                if (session.accepts(generation) && current != null) current.onAssist(next, shown);
            }
        });
    }

    /** 音量仅发给当前正在听的轮次，取消后丢弃已排队的波形更新。 */
    private void publishLevelLocked(float level) {
        long generation = session.current();
        main.post(() -> {
            synchronized (lock) {
                Listener current = listener;
                if (session.accepts(generation) && LISTENING.equals(state) && current != null) {
                    current.onAssistLevel(level);
                }
            }
        });
    }

    /** 通知界面是否推迟待机。 */
    private void hold(boolean hold) {
        long generation = session.current();
        main.post(() -> {
            synchronized (lock) {
                Listener current = listener;
                if (session.accepts(generation) && current != null) current.onAssistHold(hold);
            }
        });
    }
}
