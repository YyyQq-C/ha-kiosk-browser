package cn.local.tabletkiosk;

/** 将本机 PCM 响度压缩成适合声波显示的幅度，不改变录音或上传内容。 */
final class AssistAudioLevel {
    /** 平方根压缩让普通说话也能显示起伏，静音为零，极大音量不超界。 */
    static float normalize(int loudness) {
        return (float) Math.sqrt(Math.max(0f, Math.min(1f, loudness / 8000f)));
    }
}
