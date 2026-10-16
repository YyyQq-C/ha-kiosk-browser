package cn.local.tabletkiosk;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

/** 从画面顶部下拉时刷新当前页。起点不在顶部时，滑动仍交给网页。 */
public final class TopPullRefreshLayout extends FrameLayout {
    /** 下拉达到距离后松手触发刷新。 */
    public interface Listener {
        /** 用户完成一次有效下拉。 */
        void onPullRefresh();
    }

    private final int topBandPx;
    private final int triggerPx;
    private final int slopPx;
    private final ProgressBar progress;
    private Listener listener;
    private float startX;
    private float startY;
    private boolean fromTop;
    private boolean dragging;

    /** 按屏幕密度确定顶部区域和下拉距离。 */
    public TopPullRefreshLayout(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        topBandPx = Math.round(96 * density);
        triggerPx = Math.round(80 * density);
        slopPx = Math.round(16 * density);
        progress = new ProgressBar(context);
        progress.setIndeterminate(true);
        progress.setVisibility(GONE);
        if (progress.getIndeterminateDrawable() != null) {
            progress.getIndeterminateDrawable().setTint(0xFF205C60);
        }
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = Math.round(24 * density);
        addView(progress, lp);
    }

    /** 设置下拉完成后的回调。 */
    public void setListener(Listener next) {
        listener = next;
    }

    /** 显示或收起顶部进度。 */
    public void setRefreshing(boolean refreshing) {
        progress.setVisibility(refreshing ? VISIBLE : GONE);
        progress.bringToFront();
    }

    /** 起点在顶部且页面向下没有滚动时，才准备拦截下拉。 */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!isEnabled() || progress.getVisibility() == VISIBLE) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
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
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    /** 下拉超过距离后松手才刷新。 */
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!dragging) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                return true;
            case MotionEvent.ACTION_UP:
                float dy = ev.getY() - startY;
                dragging = false;
                fromTop = false;
                if (dy >= triggerPx && listener != null) {
                    setRefreshing(true);
                    listener.onPullRefresh();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                fromTop = false;
                return true;
            default:
                return true;
        }
    }

    /** 网页自身已经向下滚动时，不再抢走下拉。 */
    private boolean contentScrolled() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child != progress && child.canScrollVertically(-1)) return true;
        }
        return false;
    }
}
