package cn.local.tabletkiosk;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.ImageView;

/**
 * 从面板打开外部应用后，悬浮在其他应用上的圆形返回按钮。
 * 点一下把面板切回前台；拖动可以挪开，松手记住位置。需要「显示在其他应用上层」权限，没有时不显示。
 */
final class ReturnBubble {
    private static final int SIZE_DP = 56;
    private static final int MARGIN_DP = 12;

    private final Context app;
    private final KioskConfigStore store;
    private final int size;
    private final int margin;
    private Context windowContext;
    private WindowManager windows;
    private BubbleView view;
    private WindowManager.LayoutParams params;

    /** 绑定应用上下文和位置存储；真正的悬浮窗在 show 时才创建。 */
    ReturnBubble(Context context, KioskConfigStore store) {
        app = context.getApplicationContext();
        this.store = store;
        float density = app.getResources().getDisplayMetrics().density;
        size = Math.round(SIZE_DP * density);
        margin = Math.round(MARGIN_DP * density);
    }

    /** 返回按钮当前是否在屏幕上。 */
    boolean isShowing() {
        return view != null;
    }

    /** 显示返回按钮。没有悬浮窗权限或系统拒绝时只记日志。 */
    void show() {
        if (view != null) return;
        if (!Settings.canDrawOverlays(app)) {
            Log.i(KioskApp.LOG, "Return button skipped: overlay permission not granted");
            return;
        }
        if (!ensureWindowManager()) return;
        BubbleView next = new BubbleView(windowContext);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setTitle("HAPanel return button");
        params = lp;
        placeFromSaved();
        try {
            windows.addView(next, lp);
            view = next;
            Log.i(KioskApp.LOG, "Return button shown");
        } catch (RuntimeException e) {
            params = null;
            Log.w(KioskApp.LOG, "Failed to show return button", e);
        }
    }

    /** 收起返回按钮。面板回到前台时调用。 */
    void hide() {
        BubbleView current = view;
        view = null;
        if (current == null || windows == null) return;
        try {
            windows.removeView(current);
            Log.i(KioskApp.LOG, "Return button hidden");
        } catch (RuntimeException e) {
            Log.w(KioskApp.LOG, "Failed to remove return button", e);
        }
    }

    /** 悬浮窗要用窗口上下文创建，系统才能按显示屏给出正确尺寸。 */
    private boolean ensureWindowManager() {
        if (windows != null) return true;
        try {
            DisplayManager displays = app.getSystemService(DisplayManager.class);
            Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) return false;
            // Android 11 只有不带 Display 参数的版本，先绑定显示屏再建窗口上下文
            windowContext = app.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            windows = windowContext.getSystemService(WindowManager.class);
            return windows != null;
        } catch (RuntimeException e) {
            Log.w(KioskApp.LOG, "Return button window is not available", e);
            return false;
        }
    }

    /** 去掉状态栏、导航栏和刘海后的可放置区域，悬浮窗不会被系统栏盖住。横竖屏切换后会变。 */
    private Rect area() {
        WindowMetrics metrics = windows.getCurrentWindowMetrics();
        Rect bounds = new Rect(metrics.getBounds());
        Insets bars = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        bounds.set(bounds.left + bars.left, bounds.top + bars.top, bounds.right - bars.right, bounds.bottom - bars.bottom);
        return bounds;
    }

    /** 按保存的比例摆放，默认在右侧中间。 */
    private void placeFromSaved() {
        Rect area = area();
        params.x = area.left + Math.round(FloatingPosition.coordinate(store.returnButtonX(), area.width(), size, margin));
        params.y = area.top + Math.round(FloatingPosition.coordinate(store.returnButtonY(), area.height(), size, margin));
    }

    /** 拖动中移动到指定位置，限制在可放置区域内。 */
    private void moveTo(float x, float y) {
        if (view == null || params == null) return;
        Rect area = area();
        params.x = area.left + Math.round(FloatingPosition.coordinate(
                FloatingPosition.relative(x - area.left, area.width(), size, margin), area.width(), size, margin));
        params.y = area.top + Math.round(FloatingPosition.coordinate(
                FloatingPosition.relative(y - area.top, area.height(), size, margin), area.height(), size, margin));
        update();
    }

    /** 把新位置交给窗口管理器。 */
    private void update() {
        if (view == null || params == null) return;
        try {
            windows.updateViewLayout(view, params);
        } catch (RuntimeException e) {
            Log.w(KioskApp.LOG, "Failed to move return button", e);
        }
    }

    /** 松手后按比例保存，横竖屏切换后仍在相近位置。 */
    private void savePosition() {
        if (params == null) return;
        Rect area = area();
        store.saveReturnButtonPosition(
                FloatingPosition.relative(params.x - area.left, area.width(), size, margin),
                FloatingPosition.relative(params.y - area.top, area.height(), size, margin));
    }

    /** 圆形按钮：白底、阴影、中间是本应用图标。 */
    private final class BubbleView extends ImageView {
        private final int slop;
        private float downX;
        private float downY;
        private int originX;
        private int originY;
        private boolean dragging;

        BubbleView(Context context) {
            super(context);
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(Color.WHITE);
            setBackground(bg);
            int pad = Math.round(size * 0.12f);
            setPadding(pad, pad, pad, pad);
            setScaleType(ScaleType.FIT_CENTER);
            setImageResource(R.mipmap.ic_launcher);
            setElevation(margin / 2f);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline outline) {
                    outline.setOval(0, 0, v.getWidth(), v.getHeight());
                }
            });
            setClipToOutline(true);
            setAlpha(0.92f);
            setContentDescription(AppLocales.wrap(context).getString(R.string.return_to_panel));
        }

        /** 轻点或无障碍服务点击时把面板切回前台。 */
        @Override
        public boolean performClick() {
            super.performClick();
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            Log.i(KioskApp.LOG, "Return button tapped");
            AppLauncher.showPanel(app);
            return true;
        }

        /** 横竖屏切换后按保存的比例重新摆放。 */
        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            if (params == null) return;
            placeFromSaved();
            update();
        }

        /** 轻点回到面板；移动超过阈值后改为拖动，不触发返回。 */
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (params == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    originX = params.x;
                    originY = params.y;
                    dragging = false;
                    setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!dragging && dx * dx + dy * dy > slop * slop) {
                        dragging = true;
                        setPressed(false);
                    }
                    if (dragging) moveTo(originX + dx, originY + dy);
                    return true;
                case MotionEvent.ACTION_UP:
                    setPressed(false);
                    if (dragging) savePosition();
                    else performClick();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    setPressed(false);
                    if (dragging) savePosition();
                    dragging = false;
                    return true;
                default:
                    return true;
            }
        }
    }
}
