package cn.local.tabletkiosk;

import android.content.Context;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import java.util.Collections;
import java.util.List;

/**
 * 包住网页的容器：从左边缘起手的手势不交给网页，向右滑够距离时打开设置；
 * 几乎没有移动的轻点会原样还给网页，避免左侧按钮点不动。
 */
public final class EdgeSwipeLayout extends FrameLayout {
    /** 手势回调。 */
    public interface Listener {
        /** 任意位置被触摸，用于重置空闲和无人计时。 */
        void onUserTouch();

        /** 左边缘滑出设置。 */
        void onOpenSettings();
    }

    private static final int EDGE_DP = 24;
    private static final int OPEN_DP = 80;
    private static final int TAP_DP = 12;
    private static final int EXCLUSION_HEIGHT_DP = 200;

    private final EdgeSwipe swipe;
    private final int edgeWidthPx;
    private final int exclusionHeightPx;
    private final Rect exclusionRect = new Rect();
    private final List<Rect> exclusionRects = Collections.singletonList(exclusionRect);
    private Listener listener;
    private View tapTarget;
    private boolean edgeEnabled = true;
    private boolean replaying;

    /** 按屏幕密度换算手势阈值。 */
    public EdgeSwipeLayout(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        edgeWidthPx = Math.round(EDGE_DP * density);
        exclusionHeightPx = Math.round(EXCLUSION_HEIGHT_DP * density);
        swipe = new EdgeSwipe(edgeWidthPx, OPEN_DP * density, TAP_DP * density);
    }

    /** 设置手势回调。 */
    public void setListener(Listener next) {
        listener = next;
    }

    /** 设置轻点需要还给的网页视图；网页重建后要重新设置。 */
    public void setTapTarget(View target) {
        tapTarget = target;
    }

    /** 设置页打开时暂停边缘检测。 */
    public void setEdgeEnabled(boolean enabled) {
        edgeEnabled = enabled;
        if (!enabled) swipe.cancel();
    }

    /** 每次按下都通知外部有人操作，再按常规流程分发。 */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!replaying && ev.getActionMasked() == MotionEvent.ACTION_DOWN && listener != null) {
            listener.onUserTouch();
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 起点在左边缘时拦截整次手势，网页收不到。 */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!edgeEnabled || replaying) return false;
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) return swipe.onDown(ev.getX(), ev.getY());
        return swipe.isTracking();
    }

    /** 处理被拦截的边缘手势。 */
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return swipe.isTracking();
            case MotionEvent.ACTION_MOVE:
                swipe.onMove(ev.getX(), ev.getY());
                return true;
            case MotionEvent.ACTION_UP:
                EdgeSwipe.Result result = swipe.onUp(ev.getX(), ev.getY());
                if (result == EdgeSwipe.Result.OPEN && listener != null) listener.onOpenSettings();
                else if (result == EdgeSwipe.Result.TAP) replayTap(swipe.downX(), swipe.downY());
                return true;
            case MotionEvent.ACTION_CANCEL:
                swipe.cancel();
                return true;
            default:
                return swipe.isTracking();
        }
    }

    /** 在左边缘中段排除系统返回手势，让边缘滑动能到达本应用。 */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (!changed) return;
        int height = bottom - top;
        int span = Math.min(exclusionHeightPx, height);
        int start = (height - span) / 2;
        exclusionRect.set(0, start, edgeWidthPx, start + span);
        setSystemGestureExclusionRects(exclusionRects);
    }

    /** 把边缘轻点按手指输入重新发给网页。 */
    private void replayTap(float x, float y) {
        View target = tapTarget;
        if (target == null) return;
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = finger(downTime, downTime, MotionEvent.ACTION_DOWN, x, y);
        MotionEvent up = finger(downTime, downTime + 40, MotionEvent.ACTION_UP, x, y);
        replaying = true;
        try {
            target.dispatchTouchEvent(down);
            target.dispatchTouchEvent(up);
        } finally {
            replaying = false;
            down.recycle();
            up.recycle();
        }
    }

    /** 构造手指类型的单点触摸事件。 */
    private static MotionEvent finger(long downTime, long eventTime, int action, float x, float y) {
        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = 0;
        props.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = 1f;
        coords.size = 1f;
        return MotionEvent.obtain(downTime, eventTime, action, 1,
                new MotionEvent.PointerProperties[]{props}, new MotionEvent.PointerCoords[]{coords},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }
}
