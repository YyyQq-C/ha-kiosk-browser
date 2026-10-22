package cn.local.tabletkiosk;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageButton;

/** 点按说话，长按后拖动；取消手势不保存位置，也不误触发语音。 */
final class MovableAssistButton extends ImageButton {
    private final KioskConfigStore store;
    private final Runnable activity;
    private final Runnable positionChanged;
    private final int margin;
    private final int slop;
    private float relativeX;
    private float relativeY;
    private float downX;
    private float downY;
    private float originX;
    private float originY;
    private boolean dragging;
    private boolean moved;
    private View container;
    private final OnLayoutChangeListener containerLayout = (v, l, t, r, b, ol, ot, or, ob) -> {
        if (r - l != or - ol || b - t != ob - ot) restorePosition();
    };

    /** 绑定本机位置存储和交互通知，默认位置仍在右下角。 */
    MovableAssistButton(Context context, KioskConfigStore store, Runnable activity, Runnable positionChanged) {
        super(context);
        this.store = store;
        this.activity = activity;
        this.positionChanged = positionChanged;
        margin = Math.round(20 * getResources().getDisplayMetrics().density);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        relativeX = store.assistButtonX();
        relativeY = store.assistButtonY();
        setOnLongClickListener(v -> {
            if (moved) return false;
            dragging = true;
            setPressed(false);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            return true;
        });
    }

    /** 布局完成后恢复位置；父容器改变尺寸时重新按比例定位。 */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getParent() instanceof View) {
            container = (View) getParent();
            container.addOnLayoutChangeListener(containerLayout);
            post(this::restorePosition);
        }
    }

    /** 图标移除时清理布局监听和待触发长按。 */
    @Override
    protected void onDetachedFromWindow() {
        cancelLongPress();
        if (container != null) container.removeOnLayoutChangeListener(containerLayout);
        container = null;
        super.onDetachedFromWindow();
    }

    /** 图标首次测量、重新显示或尺寸改变时恢复比例位置。 */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        restorePosition();
    }

    /** 长按之前保留正常点击；拖动或提前滑动都不能触发点击说话。 */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = false;
                moved = false;
                downX = event.getRawX();
                downY = event.getRawY();
                originX = getX();
                originY = getY();
                activity.run();
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (dragging) {
                    moveTo(originX + dx, originY + dy);
                    return true;
                }
                if (dx * dx + dy * dy > slop * slop) {
                    moved = true;
                    cancelLongPress();
                    setPressed(false);
                }
                break;
            case MotionEvent.ACTION_UP:
                if (dragging || moved) {
                    cancelLongPress();
                    setPressed(false);
                    if (dragging) {
                        moveTo(originX + event.getRawX() - downX, originY + event.getRawY() - downY);
                        relativeX = FloatingPosition.relative(getX(), container.getWidth(), getWidth(), margin);
                        relativeY = FloatingPosition.relative(getY(), container.getHeight(), getHeight(), margin);
                        store.saveAssistButtonPosition(relativeX, relativeY);
                    }
                    dragging = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                }
                getParent().requestDisallowInterceptTouchEvent(false);
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                // 中断和多指操作都丢弃本次拖动，不保存半途位置。
                cancelLongPress();
                setPressed(false);
                moved = true;
                dragging = false;
                restorePosition();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    /** 按上次保存的比例恢复坐标，默认比例 1 表示右下角。 */
    private void restorePosition() {
        if (container == null || getWidth() <= 0 || container.getWidth() <= 0 || container.getHeight() <= 0) return;
        setX(FloatingPosition.coordinate(relativeX, container.getWidth(), getWidth(), margin));
        setY(FloatingPosition.coordinate(relativeY, container.getHeight(), getHeight(), margin));
        positionChanged.run();
    }

    /** 把拖动位置限制在父容器边界内，并通知外层更新布局。 */
    private void moveTo(float x, float y) {
        if (container == null) return;
        setX(FloatingPosition.coordinate(FloatingPosition.relative(x, container.getWidth(), getWidth(), margin),
                container.getWidth(), getWidth(), margin));
        setY(FloatingPosition.coordinate(FloatingPosition.relative(y, container.getHeight(), getHeight(), margin),
                container.getHeight(), getHeight(), margin));
        positionChanged.run();
    }
}
