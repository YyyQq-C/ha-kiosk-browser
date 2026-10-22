package cn.local.tabletkiosk;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 显示语音所处阶段、真实声波、识别及回答内容，并提供明确取消入口。 */
final class AssistPanel extends LinearLayout {
    private final TextView title;
    private final TextView detail;
    private final AssistWaveView wave;

    /** 创建底部语音浮层，触摸面板本身不会穿透到家居控制页面。 */
    AssistPanel(Context context, Runnable cancel) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(24), dp(16), dp(16), dp(16));
        setClickable(true);
        setElevation(dp(12));
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xf51c2c40, 0xf5161d30});
        background.setCornerRadius(dp(28));
        background.setStroke(dp(1), 0x664fe0e8);
        setBackground(background);
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(context);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        header.addView(title, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        Button close = new Button(context);
        close.setText("×");
        close.setTextSize(26);
        close.setTextColor(0xffb6c8da);
        close.setPadding(0, 0, 0, 0);
        close.setMinWidth(0);
        close.setMinimumWidth(0);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setContentDescription(context.getString(R.string.assist_panel_cancel));
        close.setOnClickListener(v -> cancel.run());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        addView(header, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)));
        wave = new AssistWaveView(context);
        addView(wave, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(72)));
        detail = new TextView(context);
        detail.setTextColor(0xffbecbdd);
        detail.setTextSize(15);
        detail.setGravity(Gravity.CENTER);
        detail.setMaxLines(3);
        detail.setPadding(0, dp(6), dp(8), dp(4));
        addView(detail, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        setVisibility(GONE);
    }

    /** 连接、听写、思考和播报共用同一面板，取消或结束立即收起。 */
    void showState(String state, String text) {
        if (AssistRuntime.IDLE.equals(state)) {
            wave.setState(state);
            setVisibility(GONE);
            return;
        }
        int label = AssistRuntime.LISTENING.equals(state) ? R.string.assist_panel_listening
                : AssistRuntime.THINKING.equals(state) ? R.string.assist_panel_thinking : R.string.assist_panel_speaking;
        title.setText(label);
        detail.setText(text == null || text.isEmpty() ? getContext().getString(R.string.assist_panel_hint) : text);
        wave.setState(state);
        setVisibility(VISIBLE);
    }

    /** 听音阶段把本机录音音量交给声波绘制，不改变语音传输。 */
    void setLevel(float level) {
        wave.setLevel(level);
    }

    /** dp 转为当前屏幕像素。 */
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
