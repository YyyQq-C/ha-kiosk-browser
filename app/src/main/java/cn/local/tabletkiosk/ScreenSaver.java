package cn.local.tabletkiosk;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

/** 待机时盖住网页的屏保。点击只通知退出，不把这次点击交给下面的页面。 */
final class ScreenSaver extends FrameLayout {
    private final TextView clock;
    private final TextView date;
    private final TextView lunar;
    private final TextView weather;
    private final DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm");
    private final DateTimeFormatter dateFormat;
    /** 屏保天气开关打开时为 true。关掉后异步结果不能再把这一行画出来。 */
    private boolean weatherWanted;

    /** 创建居中的时间、日期、农历和天气。初始隐藏。 */
    ScreenSaver(Context context, Runnable onDismiss) {
        super(context);
        setBackgroundColor(Color.rgb(12, 18, 22));
        setClickable(true);
        setVisibility(GONE);
        setOnClickListener(v -> onDismiss.run());

        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        if (locale == null) locale = Locale.getDefault();
        dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Math.round(28 * getResources().getDisplayMetrics().density);
        column.setPadding(pad, pad, pad, pad);

        clock = line(context, 92, Color.WHITE, true);
        date = line(context, 22, Color.rgb(214, 224, 222), false);
        lunar = line(context, 20, Color.rgb(168, 184, 182), false);
        weather = line(context, 28, Color.WHITE, false);
        column.addView(clock);
        column.addView(date);
        column.addView(lunar);
        column.addView(weather);
        addView(column, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    /**
     * 显示或收起，并刷新时间、日期。
     * 农历关掉时不占位。天气关掉或没有可显示文字时不占一行，也不写占位文案。
     */
    void setShown(boolean shown, LocalDateTime now, String lunarText, String weatherText,
                  boolean showLunar, boolean showWeather) {
        if (!shown) {
            setVisibility(GONE);
            return;
        }
        clock.setText(timeFormat.format(now));
        date.setText(dateFormat.format(now.toLocalDate()));
        weatherWanted = showWeather;
        // 关掉的内容直接隐藏，避免屏保上留下空行或占位文案
        lunar.setVisibility(showLunar ? VISIBLE : GONE);
        if (showLunar) {
            lunar.setText(lunarText == null || lunarText.isEmpty()
                    ? getContext().getString(R.string.screensaver_lunar_unavailable) : lunarText);
        }
        applyWeather(weatherText);
        describe();
        setVisibility(VISIBLE);
    }

    /** 只更新天气一行，避免时间被清空。没有可显示文字时隐藏。天气开关关掉时不改。 */
    void setWeather(String weatherText) {
        if (getVisibility() != VISIBLE || !weatherWanted) return;
        applyWeather(weatherText);
        describe();
    }

    /** 有可显示文字才画出天气行，否则不占一行。 */
    private void applyWeather(String weatherText) {
        boolean hasWeather = weatherWanted && weatherText != null && !weatherText.isEmpty();
        weather.setVisibility(hasWeather ? VISIBLE : GONE);
        if (hasWeather) weather.setText(weatherText);
    }

    /** 无障碍描述只包含当前看得见的行。 */
    private void describe() {
        StringBuilder description = new StringBuilder();
        description.append(clock.getText()).append(' ').append(date.getText());
        if (lunar.getVisibility() == VISIBLE) description.append(' ').append(lunar.getText());
        if (weather.getVisibility() == VISIBLE) description.append(' ').append(weather.getText());
        setContentDescription(description.toString());
    }

    /** 一行居中文字。 */
    private static TextView line(Context context, int sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setTextColor(color);
        view.setTextSize(sp);
        view.setGravity(Gravity.CENTER);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        int gap = Math.round(8 * context.getResources().getDisplayMetrics().density);
        view.setPadding(0, gap, 0, gap);
        return view;
    }
}
