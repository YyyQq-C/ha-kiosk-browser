package cn.local.tabletkiosk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** 取消后排队的网络和播放回调不能干扰界面或下一轮语音。 */
public class AssistSessionTest {
    /** 取消使当前轮次的听写、错误和播放回调全部失效。 */
    @Test
    public void cancellationRejectsPendingCallbacks() {
        AssistSession session = new AssistSession();
        long listening = session.advance();
        assertTrue(session.accepts(listening));
        session.advance();
        assertFalse(session.accepts(listening));
        assertTrue(session.accepts(session.current()));
    }

    /** 快速取消再开始时，上一轮的播放结束不能结束新轮次。 */
    @Test
    public void newSessionRejectsOldPlaybackCompletion() {
        AssistSession session = new AssistSession();
        long oldPlayback = session.advance();
        session.advance();
        long next = session.advance();
        assertFalse(session.accepts(oldPlayback));
        assertTrue(session.accepts(next));
    }
}
