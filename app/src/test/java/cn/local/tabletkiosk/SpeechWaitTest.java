package cn.local.tabletkiosk;

import static org.junit.Assert.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/** 初始无语音超时、原停顿逻辑及新配置的兼容校验。 */
public class SpeechWaitTest {
    /** 默认 5 秒无声音才结束，不被 1.2 秒停顿规则提前打断。 */
    @Test
    public void silentStartTimesOutAfterFiveSeconds() {
        SpeechWait wait = new SpeechWait();
        wait.start(1000);
        wait.hear(100);
        assertFalse(wait.expired(5999, 5));
        assertTrue(wait.expired(6000, 5));
        UtteranceEnd utterance = new UtteranceEnd();
        assertFalse(utterance.finished(100, 1000));
        assertFalse(utterance.finished(100, 2200));
        assertFalse(utterance.finished(100, 6000));
    }

    /** 已开始说话后不触发初始等待超时，仍以说话后的停顿结束。 */
    @Test
    public void detectedSpeechKeepsNormalPauseDetection() {
        SpeechWait wait = new SpeechWait();
        wait.start(0);
        wait.hear(UtteranceEnd.LOUD);
        assertFalse(wait.expired(6000, 5));
        UtteranceEnd utterance = new UtteranceEnd();
        assertFalse(utterance.finished(UtteranceEnd.LOUD, 4000));
        assertFalse(utterance.finished(0, 5199));
        assertTrue(utterance.finished(0, 5200));
        wait.start(7000);
        assertTrue(wait.expired(12000, 5));
    }

    /** 修改超时秒数后按新值判定，配置复制及导入导出都保留。 */
    @Test
    public void configurableTimeoutRoundTripsAndValidates() {
        assertEquals(5, KioskConfig.defaults().speechWaitSeconds);
        Map<String,String> text = new LinkedHashMap<>(ConfigForm.toText(KioskConfig.defaults()));
        text.put(ConfigForm.SPEECH_WAIT_SECONDS, "12");
        ConfigForm.Result parsed = ConfigForm.parse(text, false, false, false, false);
        assertTrue(parsed.ok());
        assertEquals(12, parsed.config.speechWaitSeconds);
        assertEquals(12, parsed.config.toBuilder().build().speechWaitSeconds);
        SettingsFile.Document file = SettingsFile.fromJson(SettingsFile.toJson(parsed.config, "zh", ""));
        assertNull(file.error);
        assertEquals(12, file.config.speechWaitSeconds);
        assertNotEquals(KioskConfig.defaults(), parsed.config);
        SpeechWait wait = new SpeechWait();
        wait.start(0);
        assertFalse(wait.expired(5000, 12));
        assertTrue(wait.expired(12000, 12));
        text.put(ConfigForm.SPEECH_WAIT_SECONDS, "0");
        assertFalse(ConfigForm.parse(text, false, false, false, false).ok());
        text.put(ConfigForm.SPEECH_WAIT_SECONDS, "121");
        assertFalse(ConfigForm.parse(text, false, false, false, false).ok());
    }

    /** 旧设置文件没有新字段时沿用 5 秒默认值。 */
    @Test
    public void olderSettingsUseDefaultFiveSeconds() {
        Map<String,String> text = new LinkedHashMap<>(ConfigForm.toText(KioskConfig.defaults()));
        text.remove(ConfigForm.SPEECH_WAIT_SECONDS);
        assertEquals(5, ConfigForm.parse(text, false, false, false, false).config.speechWaitSeconds);
    }
}
