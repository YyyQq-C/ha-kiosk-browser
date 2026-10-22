package cn.local.tabletkiosk;

/** 开始说话后，停顿约 1.2 秒或说满 15 秒结束；初始无语音等待由单独超时处理。 */
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
        // 没有说过话时不因 1.2 秒安静而提交空录音。
        if (started < 0 && rms < LOUD) return false;
        if (started < 0) started = now;
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
