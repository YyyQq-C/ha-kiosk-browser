package cn.local.tabletkiosk;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 用 openWakeWord 的三件模型在本机判断 okay nabu。音频不离开这台平板。 */
final class OpenWakeWordEngine {
    private static final int CHUNK = 1280;
    private static final int MEL_BINS = 32;
    private static final int MEL_CONTEXT = 76;
    private static final int EMBEDDING = 96;
    private static final int WARMUP = 5;

    private final OrtEnvironment environment;
    private final OrtSession melSession;
    private final OrtSession embeddingSession;
    private final OrtSession wakeSession;
    private final String melInput;
    private final String embeddingInput;
    private final String wakeInput;
    private final int wakeFrames;
    private final ArrayList<float[]> mel = new ArrayList<>();
    private final ArrayList<float[]> embeddings = new ArrayList<>();
    private final byte[] pending = new byte[CHUNK * 2];
    private final short[] tail = new short[480];
    private int pendingBytes;
    private int scored;
    private boolean failed;

    /** 从 assets/wake 载入三个 ONNX 模型。失败时后续打分一直返回 -1。 */
    OpenWakeWordEngine(Context context) {
        OrtEnvironment env = null;
        OrtSession mel = null;
        OrtSession embedding = null;
        OrtSession wake = null;
        String melName = "";
        String embeddingName = "";
        String wakeName = "";
        int frames = 16;
        try {
            env = OrtEnvironment.getEnvironment();
            AssetManager assets = context.getAssets();
            mel = env.createSession(read(assets, "wake/melspectrogram.onnx"), new OrtSession.SessionOptions());
            embedding = env.createSession(read(assets, "wake/embedding_model.onnx"), new OrtSession.SessionOptions());
            wake = env.createSession(read(assets, "wake/okay_nabu.onnx"), new OrtSession.SessionOptions());
            melName = mel.getInputNames().iterator().next();
            embeddingName = embedding.getInputNames().iterator().next();
            wakeName = wake.getInputNames().iterator().next();
            for (int i = 0; i < MEL_CONTEXT; i++) this.mel.add(ones());
        } catch (Exception e) {
            Log.e(KioskApp.LOG, "Wake model failed to load", e);
            closeQuietly(mel);
            closeQuietly(embedding);
            closeQuietly(wake);
            mel = null;
        }
        environment = env;
        melSession = mel;
        embeddingSession = embedding;
        wakeSession = wake;
        melInput = melName;
        embeddingInput = embeddingName;
        wakeInput = wakeName;
        wakeFrames = frames;
    }

    /** 喂入小端 PCM。凑满 80 毫秒才返回 0 到 1 的分数，否则返回 -1。 */
    synchronized float push(byte[] pcm, int length) {
        if (melSession == null || failed || pcm == null || length <= 0) return -1f;
        int offset = 0;
        float score = -1f;
        while (offset < length) {
            int copy = Math.min(pending.length - pendingBytes, length - offset);
            System.arraycopy(pcm, offset, pending, pendingBytes, copy);
            pendingBytes += copy;
            offset += copy;
            if (pendingBytes == pending.length) {
                score = scoreChunk(pending);
                pendingBytes = 0;
            }
        }
        return score;
    }

    /** 释放模型。 */
    synchronized void close() {
        closeQuietly(melSession);
        closeQuietly(embeddingSession);
        closeQuietly(wakeSession);
    }

    /** 对 1280 个采样打一次分。前几次固定为 0，和 openWakeWord 的预热一致。 */
    private float scoreChunk(byte[] chunk) {
        try {
            short[] samples = new short[CHUNK];
            for (int i = 0; i < CHUNK; i++) {
                int lo = chunk[i * 2] & 0xff;
                int hi = chunk[i * 2 + 1];
                samples[i] = (short) (lo | (hi << 8));
            }
            float[] audio = new float[tail.length + CHUNK];
            for (int i = 0; i < tail.length; i++) audio[i] = tail[i];
            for (int i = 0; i < CHUNK; i++) audio[tail.length + i] = samples[i];
            System.arraycopy(samples, CHUNK - tail.length, tail, 0, tail.length);
            // 模型吃的是 int16 转成的 float，不做归一化。频谱再按官方 /10+2。
            float[] spec = run(melSession, melInput, audio, new long[] {1, audio.length});
            int frames = spec.length / MEL_BINS;
            for (int frame = 0; frame < frames; frame++) {
                float[] row = new float[MEL_BINS];
                for (int bin = 0; bin < MEL_BINS; bin++) row[bin] = spec[frame * MEL_BINS + bin] / 10f + 2f;
                mel.add(row);
            }
            trim(mel, 400);
            if (mel.size() < MEL_CONTEXT) return -1f;
            float[] window = new float[MEL_CONTEXT * MEL_BINS];
            for (int frame = 0; frame < MEL_CONTEXT; frame++) {
                float[] row = mel.get(mel.size() - MEL_CONTEXT + frame);
                System.arraycopy(row, 0, window, frame * MEL_BINS, MEL_BINS);
            }
            float[] embedding = run(embeddingSession, embeddingInput, window,
                    new long[] {1, MEL_CONTEXT, MEL_BINS, 1});
            if (embedding.length < EMBEDDING) return -1f;
            float[] vector = new float[EMBEDDING];
            System.arraycopy(embedding, embedding.length - EMBEDDING, vector, 0, EMBEDDING);
            embeddings.add(vector);
            trim(embeddings, 120);
            scored++;
            if (scored <= WARMUP || embeddings.size() < wakeFrames) return 0f;
            float[] features = new float[wakeFrames * EMBEDDING];
            for (int frame = 0; frame < wakeFrames; frame++) {
                float[] row = embeddings.get(embeddings.size() - wakeFrames + frame);
                System.arraycopy(row, 0, features, frame * EMBEDDING, EMBEDDING);
            }
            float[] prediction = run(wakeSession, wakeInput, features, new long[] {1, wakeFrames, EMBEDDING});
            return prediction.length == 0 ? 0f : prediction[0];
        } catch (Exception e) {
            failed = true;
            Log.e(KioskApp.LOG, "Wake scoring stopped", e);
            return -1f;
        }
    }

    /** 跑一个浮点模型，输出拉成一维。 */
    private float[] run(OrtSession session, String input, float[] data, long[] shape) throws Exception {
        FloatBuffer buffer = FloatBuffer.wrap(data);
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, buffer, shape);
             OrtSession.Result result = session.run(Collections.singletonMap(input, tensor))) {
            OnnxTensor output = (OnnxTensor) result.get(0);
            FloatBuffer values = output.getFloatBuffer();
            float[] flat = new float[values.remaining()];
            values.get(flat);
            return flat;
        }
    }

    /** 初始频谱用 1 填充，让第一窗也能送给嵌入模型。 */
    private static float[] ones() {
        float[] row = new float[MEL_BINS];
        for (int i = 0; i < row.length; i++) row[i] = 1f;
        return row;
    }

    /** 只保留最近若干帧。 */
    private static void trim(List<float[]> rows, int max) {
        while (rows.size() > max) rows.remove(0);
    }

    /** 读出 assets 里的模型字节。 */
    private static byte[] read(AssetManager assets, String name) throws Exception {
        try (InputStream in = assets.open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    /** 关闭会话，忽略已经关闭的情况。 */
    private static void closeQuietly(OrtSession session) {
        if (session == null) return;
        try {
            session.close();
        } catch (Exception e) {
            Log.w(KioskApp.LOG, "Wake session close failed", e);
        }
    }
}
