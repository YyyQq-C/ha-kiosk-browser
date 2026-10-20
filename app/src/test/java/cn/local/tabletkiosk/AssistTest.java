package cn.local.tabletkiosk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Assist 分帧、鉴权文本和管道事件解析。 */
public class AssistTest {
    /** 音频块以通道号开头，结束帧只有这一字节。 */
    @Test
    public void framesUseHandlerByte() {
        byte[] chunk = AssistProtocol.audioChunk(1, new byte[] {0x11, 0x22, 0x33}, 3);
        assertArrayEquals(new byte[] {1, 0x11, 0x22, 0x33}, chunk);
        assertArrayEquals(new byte[] {1}, AssistProtocol.audioEnd(1));
    }

    /** 运行命令从听写到合成，并带上唤醒词。 */
    @Test
    public void runMessageStartsAtSpeech() {
        String json = AssistProtocol.runMessage(7, "", AssistProtocol.WAKE_PHRASE);
        assertTrue(json.contains("\"type\":\"assist_pipeline/run\""));
        assertTrue(json.contains("\"start_stage\":\"stt\""));
        assertTrue(json.contains("\"end_stage\":\"tts\""));
        assertTrue(json.contains("\"sample_rate\":16000"));
        assertTrue(json.contains("\"wake_word_phrase\":\"okay nabu\""));
        assertFalse(json.contains("conversation_id"));
        assertTrue(AssistProtocol.runMessage(7, "abc", "").contains("\"conversation_id\":\"abc\""));
    }

    /** 登录、听写、回答、合成地址和错误都能读出来。 */
    @Test
    public void parsesPipelineEvents() {
        assertEquals("auth_required", AssistProtocol.parse("{\"type\":\"auth_required\"}").kind);
        AssistProtocol.Message start = AssistProtocol.parse(
                "{\"id\":1,\"type\":\"event\",\"event\":{\"type\":\"run-start\",\"data\":{"
                        + "\"conversation_id\":\"c1\",\"runner_data\":{\"stt_binary_handler_id\":1}}}}");
        assertEquals("run-start", start.eventType);
        assertEquals(1, start.handlerId);
        assertEquals("c1", start.conversationId);
        AssistProtocol.Message heard = AssistProtocol.parse(
                "{\"type\":\"event\",\"event\":{\"type\":\"stt-end\",\"data\":{\"stt_output\":{\"text\":\"打开主卧空调\"}}}}");
        assertEquals("打开主卧空调", heard.transcript);
        AssistProtocol.Message reply = AssistProtocol.parse(
                "{\"type\":\"event\",\"event\":{\"type\":\"intent-end\",\"data\":{\"intent_output\":{\"response\":"
                        + "{\"speech\":{\"plain\":{\"speech\":\"好的\"}}}}}}}");
        assertEquals("好的", reply.reply);
        AssistProtocol.Message audio = AssistProtocol.parse(
                "{\"type\":\"event\",\"event\":{\"type\":\"tts-end\",\"data\":{\"tts_output\":{\"url\":\"/api/tts_proxy/a\"}}}}");
        assertEquals("https://ha.example/api/tts_proxy/a",
                AssistProtocol.absoluteUrl("https://ha.example", audio.ttsUrl));
        AssistProtocol.Message error = AssistProtocol.parse(
                "{\"type\":\"event\",\"event\":{\"type\":\"error\",\"data\":{\"code\":\"stt-no-text\",\"message\":\"empty\"}}}");
        assertEquals("error", error.eventType);
        assertEquals("empty", error.error);
    }

    /** 连续两次超过阈值才触发，暂停和冷却期间不触发。 */
    @Test
    public void wakeNeedsRepeatedScores() {
        WakeWordGate gate = new WakeWordGate();
        float line = WakeWordGate.threshold(50);
        assertEquals(0.5f, line, 0.001f);
        assertFalse(gate.hear(0.9f, line, 0, false));
        assertTrue(gate.hear(0.9f, line, 10, false));
        assertFalse(gate.hear(0.9f, line, 20, false));
        assertFalse(gate.hear(0.9f, line, 1600, true));
        assertFalse(gate.hear(0.1f, line, 1700, false));
        assertFalse(gate.hear(0.9f, line, 1800, false));
        assertTrue(gate.hear(0.9f, line, 1900, false));
        assertTrue(WakeWordGate.threshold(0) > 1f);
    }

    /** 静音和最长时长都会结束这一句。 */
    @Test
    public void utteranceEndsOnSilenceOrLimit() {
        UtteranceEnd end = new UtteranceEnd();
        assertFalse(end.finished(20, 0));
        assertFalse(end.finished(UtteranceEnd.LOUD, 500));
        assertFalse(end.finished(20, 500 + UtteranceEnd.SILENCE_MS - 1));
        assertTrue(end.finished(20, 500 + UtteranceEnd.SILENCE_MS));
        UtteranceEnd limited = new UtteranceEnd();
        assertFalse(limited.finished(UtteranceEnd.LOUD, 0));
        assertTrue(limited.finished(UtteranceEnd.LOUD, UtteranceEnd.MAX_MS));
        byte[] pcm = new byte[] {0x2c, 0x01, 0x00, 0x00};
        assertEquals(150, UtteranceEnd.rms(pcm, 4));
    }
}
