package cn.local.tabletkiosk;

/** 判断一句语音该停：静音约 1.2 秒，或最长 15 秒。 */
public final class UtteranceEnd {
    public static final int SILENCE_MS = 1200;
    public static final int MAX_MS = 15000;
    /** 16-bit PCM 的响度门槛，低于它视为静音。 */
    public static final int LOUD = 700;

    private long started = -1;
    private long lastLoud = -1;

    /** 重新开始一句。 */
    public void reset() {
        started = -1;
        lastLoud = -1;
    }

    /** 喂入当前块的响度。到点返回 true。 */
    public boolean finished(int rms, long now) {
        if (started < 0) {
            started = now;
            lastLoud = now;
        }
        if (rms >= LOUD) lastLoud = now;
        return now - started >= MAX_MS || now - lastLoud >= SILENCE_MS;
    }

    /** 计算小端 16-bit PCM 的平均绝对值，当作响度。 */
    public static int rms(byte[] pcm, int length) {
        if (pcm == null || length < 2) return 0;
        int samples = length / 2;
        long sum = 0;
        for (int i = 0; i < samples; i++) {
            int lo = pcm[i * 2] & 0xff;
            int hi = pcm[i * 2 + 1];
            sum += Math.abs((short) (lo | (hi << 8)));
        }
        return (int) (sum / samples);
    }
}
