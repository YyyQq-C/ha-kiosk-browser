package cn.local.tabletkiosk;

/** 为语音轮次生成编号，取消或开始新轮次后丢弃旧回调。 */
final class AssistSession {
    private long generation;

    /** 新建轮次或使当前轮次失效。调用方持有语音状态锁。 */
    long advance() {
        return ++generation;
    }

    /** 获取当前轮次编号。 */
    long current() {
        return generation;
    }

    /** 判断网络、界面和播放回调是否仍属于当前轮次。 */
    boolean accepts(long candidate) {
        return generation == candidate;
    }
}
