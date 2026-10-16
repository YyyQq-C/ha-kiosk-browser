package cn.local.tabletkiosk;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;

/** 界面语言：未选择时跟随系统，选过之后固定为简体中文或英文。 */
public final class AppLocales {
    public static final String ZH = "zh";
    public static final String EN = "en";
    /** 前台服务若收到该动作会刷新通知并重新发布 MQTT。设置面板切换语言不会发送它。 */
    public static final String ACTION_REFRESH = "cn.local.tabletkiosk.LOCALE_REFRESH";
    private static final String PREFS = "kiosk_config";
    private static final String KEY = "ui_language";

    private AppLocales() {
    }

    /** 已保存的语言；没选过返回空字符串。 */
    public static String saved(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
        return value == null ? "" : value;
    }

    /** 记下用户选择。 */
    public static void save(Context context, String language) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language).commit();
    }

    /** 当前应使用的语言。系统是英语且用户没选过时用英语，否则用简体中文。 */
    public static String effective(Context context) {
        return choose(saved(context), Locale.getDefault().getLanguage());
    }

    /** 按已保存值和系统语言决定 zh 或 en。 */
    public static String choose(String saved, String systemLanguage) {
        if (ZH.equals(saved) || EN.equals(saved)) return saved;
        return "en".equals(systemLanguage) ? EN : ZH;
    }

    /** 从当前有效语言切到另一种。 */
    public static String toggle(String language) {
        return EN.equals(language) ? ZH : EN;
    }

    /** 语言按钮显示当前语言：英文界面为 EN，其余为中文。 */
    public static String buttonLabel(String language) {
        return EN.equals(language) ? "EN" : "中文";
    }

    /** 按已保存的语言包装 Context；没选过则保持系统语言。 */
    public static Context wrap(Context base) {
        String saved = saved(base);
        if (!ZH.equals(saved) && !EN.equals(saved)) return base;
        Locale locale = EN.equals(saved) ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        return base.createConfigurationContext(config);
    }

    /**
     * 只为设置面板准备指定语言的 Context。
     * 不调用 Locale.setDefault，因此不会立刻改主界面、网页或 TTS。
     */
    public static Context forLanguage(Context base, String language) {
        Locale locale = EN.equals(language) ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        Context localized = base.createConfigurationContext(config);
        return new android.view.ContextThemeWrapper(localized, R.style.Theme_TabletKiosk);
    }
}
