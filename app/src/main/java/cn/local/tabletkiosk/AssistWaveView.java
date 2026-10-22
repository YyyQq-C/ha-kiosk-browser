package cn.local.tabletkiosk;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** 语音面板声波：听音幅度来自真实麦克风，其他阶段采用状态动效。 */
final class AssistWaveView extends View {
    /** 图标和声波共用的渐变端点。 */
    static final int CYAN = 0xff37def0;
    static final int VIOLET = 0xffaa8ef0;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
    private String state = AssistRuntime.IDLE;
    private float targetLevel;
    private float level;
    private float phase;
    private boolean visible;

    /** 初始化轻量帧动画，所有帧只重绘本声波区域。 */
    AssistWaveView(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        animator.setDuration(1400);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> {
            phase = (float) value.getAnimatedValue() * (float) (Math.PI * 2);
            level += (targetLevel - level) * (targetLevel > level ? 0.3f : 0.1f);
            invalidate();
        });
    }

    /** 切换状态时清空上轮音量，避免残留大波峰。 */
    void setState(String next) {
        if (!state.equals(next)) {
            state = next;
            targetLevel = 0;
            level = 0;
        }
        syncAnimation();
        invalidate();
    }

    /** 接收主线程节流后的真实录音幅度。 */
    void setLevel(float value) {
        targetLevel = Math.max(0f, Math.min(1f, value));
        if (!ValueAnimator.areAnimatorsEnabled()) level = targetLevel;
        invalidate();
    }

    /** 面板隐藏、窗口不可见时立即停掉动画。 */
    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        visible = isVisible;
        syncAnimation();
    }

    /** 离开窗口时释放动画回调，避免后台持续重绘。 */
    @Override
    protected void onDetachedFromWindow() {
        visible = false;
        animator.cancel();
        super.onDetachedFromWindow();
    }

    /** 只在面板可见且有活动语音时运行，尊重系统关闭动画的设置。 */
    private void syncAnimation() {
        boolean run = visible && !AssistRuntime.IDLE.equals(state) && ValueAnimator.areAnimatorsEnabled();
        if (run && !animator.isStarted()) animator.start();
        else if (!run) animator.cancel();
    }

    /** 绘制青紫渐变的圆角声波；静音时仅保留轻微待听起伏。 */
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int count = 29;
        float step = getWidth() / (float) count;
        float width = Math.min(step * 0.5f, 7 * getResources().getDisplayMetrics().density);
        float center = getHeight() / 2f;
        for (int i = 0; i < count; i++) {
            float t = i / (float) (count - 1);
            float envelope = (float) Math.sin(Math.PI * t);
            float ripple = 0.45f + 0.55f * Math.abs((float) Math.sin(phase * 2 - i * 0.58f));
            float amplitude = AssistRuntime.LISTENING.equals(state) ? 0.20f + level * 0.72f
                    : AssistRuntime.THINKING.equals(state) ? 0.25f : 0.55f;
            float height = 5 * getResources().getDisplayMetrics().density
                    + getHeight() * 0.8f * envelope * ripple * amplitude;
            paint.setColor(Color.rgb(
                    (int) (Color.red(CYAN) + (Color.red(VIOLET) - Color.red(CYAN)) * t),
                    (int) (Color.green(CYAN) + (Color.green(VIOLET) - Color.green(CYAN)) * t),
                    (int) (Color.blue(CYAN) + (Color.blue(VIOLET) - Color.blue(CYAN)) * t)));
            float x = step * (i + 0.5f);
            canvas.drawRoundRect(x - width / 2, center - height / 2,
                    x + width / 2, center + height / 2, width / 2, width / 2, paint);
        }
    }
}
