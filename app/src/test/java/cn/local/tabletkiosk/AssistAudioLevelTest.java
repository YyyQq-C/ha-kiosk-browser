package cn.local.tabletkiosk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** 声波必须反映实际输入，静音和异常响度不能产生非法幅度。 */
public class AssistAudioLevelTest {
    /** 静音为零，正常语音幅度递增，大音量封顶。 */
    @Test
    public void amplitudeTracksRealInputWithinBounds() {
        assertEquals(0f, AssistAudioLevel.normalize(0), 0.001f);
        assertEquals(0f, AssistAudioLevel.normalize(-1), 0.001f);
        assertTrue(AssistAudioLevel.normalize(700) > AssistAudioLevel.normalize(100));
        assertTrue(AssistAudioLevel.normalize(4000) > AssistAudioLevel.normalize(700));
        assertEquals(1f, AssistAudioLevel.normalize(32768), 0.001f);
    }
}
