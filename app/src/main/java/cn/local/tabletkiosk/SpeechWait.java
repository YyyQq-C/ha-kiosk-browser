package cn.local.tabletkiosk;

/** 只计算说出第一句话前的等待；已听到声音后交给原有停顿逻辑。 */
final class SpeechWait {
    private long started;
    private boolean heard;

    /** 唤醒或点击开始时记录单调时间。 */
    void start(long now) {
        started = now;
        heard = false;
    }

    /** 沿用现有录音响度门槛判断是否开始说话。 */
    void hear(int loudness) {
        if (loudness >= UtteranceEnd.LOUD) heard = true;
    }

    /** 尚未说话且超过配置时间时，应取消本轮而不是发送空语音。 */
    boolean expired(long now, int seconds) {
        return !heard && now - started >= seconds * 1000L;
    }
}
