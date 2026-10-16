package cn.local.tabletkiosk;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

/** 从画面顶部下拉时刷新当前页。下拉过程中页面跟着下移，松手不够则弹回。 */
public final class TopPullRefreshLayout extends FrameLayout {
    /** 下拉达到距离后松手触发刷新。 */
    public interface Listener {
        /** 用户完成一次有效下拉。 */
        void onPullRefresh();
    }

    private final int topBandPx;
    private final int triggerPx;
    private final int holdPx;
    private final int maxPx;
    private final int slopPx;
    private final View mark;
    private final ProgressBar progress;
    private Listener listener;
    private ValueAnimator animator;
    private float startX;
    private float startY;
    private float offset;
    private boolean fromTop;
    private boolean dragging;
    private boolean refreshing;

    /** 按屏幕密度确定顶部区域和下拉距离。 */
    public TopPullRefreshLayout(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        topBandPx = Math.round(96 * density);
        triggerPx = Math.round(80 * density);
        holdPx = Math.round(72 * density);
        maxPx = Math.round(160 * density);
        slopPx = Math.round(12 * density);
        setClipChildren(false);
        setClipToPadding(false);

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFF205C60);
        mark = new View(context);
        mark.setBackground(circle);
        mark.setVisibility(GONE);
        int markSize = Math.round(28 * density);
        LayoutParams markLp = new LayoutParams(markSize, markSize);
        markLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        addView(mark, markLp);

        progress = new ProgressBar(context);
        progress.setIndeterminate(true);
        progress.setVisibility(GONE);
        if (progress.getIndeterminateDrawable() != null) {
            progress.getIndeterminateDrawable().setTint(0xFFFFFFFF);
        }
        LayoutParams progressLp = new LayoutParams(markSize, markSize);
        progressLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        addView(progress, progressLp);
    }

    /** 设置下拉完成后的回调。 */
    public void setListener(Listener next) {
        listener = next;
    }

    /** 刷新结束时把页面弹回原位。刷新开始时保持拉开的位置。 */
    public void setRefreshing(boolean refreshing) {
        this.refreshing = refreshing;
        if (refreshing) applyOffset(Math.max(offset, holdPx));
        else settle(0);
    }

    /** 起点在顶部且页面向下没有滚动时，才准备拦截下拉。 */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!isEnabled() || refreshing) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelSettle();
                startX = ev.getX();
                startY = ev.getY();
                fromTop = startY <= topBandPx && !contentScrolled();
                dragging = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!fromTop || dragging) return dragging;
                float dx = ev.getX() - startX;
                float dy = ev.getY() - startY;
                if (dy > slopPx && dy > Math.abs(dx)) {
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    /** 页面跟随手指下移；超过距离后松手才刷新。 */
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!dragging) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                applyOffset(damped(ev.getY() - startY));
                return true;
            case MotionEvent.ACTION_UP:
                float pulled = offset;
                dragging = false;
                fromTop = false;
                if (pulled >= triggerPx && listener != null) {
                    refreshing = true;
                    applyOffset(Math.max(pulled, holdPx));
                    listener.onPullRefresh();
                } else {
                    settle(0);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                fromTop = false;
                if (!refreshing) settle(0);
                return true;
            default:
                return true;
        }
    }

    /** 下拉越深阻力越大，避免页面被拉出太远。 */
    private float damped(float dy) {
        if (dy <= 0) return 0;
        return maxPx * (1f - (float) Math.exp(-dy / maxPx));
    }

    /** 移动网页，并在拉开的空白处显示指示。 */
    private void applyOffset(float next) {
        offset = Math.max(0f, Math.min(maxPx, next));
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child != mark && child != progress) child.setTranslationY(offset);
        }
        float center = Math.max(0f, (offset - mark.getHeight()) / 2f);
        if (refreshing) {
            mark.setVisibility(GONE);
            progress.setVisibility(VISIBLE);
            progress.setTranslationY(center);
            progress.bringToFront();
            return;
        }
        progress.setVisibility(GONE);
        if (offset <= 1f) {
            mark.setVisibility(GONE);
            return;
        }
        float fraction = Math.min(1f, offset / triggerPx);
        mark.setVisibility(VISIBLE);
        mark.setAlpha(0.35f + 0.65f * fraction);
        mark.setScaleX(0.45f + 0.55f * fraction);
        mark.setScaleY(0.45f + 0.55f * fraction);
        mark.setTranslationY(center);
        mark.bringToFront();
    }

    /** 松手未达标，或刷新完成后，把页面收回。 */
    private void settle(float target) {
        cancelSettle();
        animator = ValueAnimator.ofFloat(offset, target);
        animator.setDuration(220);
        animator.addUpdateListener(animation -> applyOffset((float) animation.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            /** 收回结束后收起指示。 */
            @Override
            public void onAnimationEnd(Animator animation) {
                animator = null;
                if (!refreshing) applyOffset(target);
            }
        });
        animator.start();
    }

    /** 新的手势开始时停掉回弹。 */
    private void cancelSettle() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    /** 网页自身已经向下滚动时，不再抢走下拉。 */
    private boolean contentScrolled() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child != mark && child != progress && child.canScrollVertically(-1)) return true;
        }
        return false;
    }
}
