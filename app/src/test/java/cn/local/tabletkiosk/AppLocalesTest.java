package cn.local.tabletkiosk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 界面语言的默认选择。 */
public class AppLocalesTest {
    /** 用户选过的语言优先；没选过时只有系统英语用英语。 */
    @Test
    public void chooseKeepsSavedLanguage() {
        assertEquals(AppLocales.ZH, AppLocales.choose(AppLocales.ZH, "en"));
        assertEquals(AppLocales.EN, AppLocales.choose(AppLocales.EN, "zh"));
        assertEquals(AppLocales.EN, AppLocales.choose("", "en"));
        assertEquals(AppLocales.ZH, AppLocales.choose("", "zh"));
        assertEquals(AppLocales.ZH, AppLocales.choose(null, "fr"));
    }

    /** 一个按钮从当前有效语言切到另一种，并显示当前语言。 */
    @Test
    public void toggleAndLabelFollowCurrentLanguage() {
        assertEquals(AppLocales.EN, AppLocales.toggle(AppLocales.ZH));
        assertEquals(AppLocales.ZH, AppLocales.toggle(AppLocales.EN));
        assertEquals(AppLocales.EN, AppLocales.toggle(""));
        assertEquals("中文", AppLocales.buttonLabel(AppLocales.ZH));
        assertEquals("EN", AppLocales.buttonLabel(AppLocales.EN));
        assertEquals("中文", AppLocales.buttonLabel(""));
    }
}
