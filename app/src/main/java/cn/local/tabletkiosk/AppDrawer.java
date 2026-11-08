package cn.local.tabletkiosk;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** 从右侧滑入的应用栏：列出设置里加的外部应用，点一个就打开。点外侧、向右滑或按返回关闭。 */
final class AppDrawer extends FrameLayout {
    /** 应用栏需要主界面提供的操作。 */
    interface Host {
        /** 用户点了一个应用。 */
        void onAppChosen(String packageName);

        /** 应用栏完全收起。 */
        void onAppDrawerClosed();
    }

    private static final int TEXT = Color.rgb(22, 40, 44);
    private static final int MUTED = Color.rgb(96, 116, 120);
    private static final int ACCENT = Color.rgb(32, 92, 96);
    private static final int COLUMNS = 3;

    private final Host host;
    private final float density;
    private final View scrim;
    private final SwipeFrame panel;
    private final TextView title;
    private final TextView hint;
    private final LinearLayout grid;
    private boolean open;

    /** 构建应用栏，初始隐藏。 */
    AppDrawer(Context context, Host host) {
        super(context);
        this.host = host;
        density = getResources().getDisplayMetrics().density;

        scrim = new View(context);
        scrim.setBackgroundColor(Color.argb(110, 0, 0, 0));
        scrim.setOnClickListener(v -> hide());
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // 只圆左侧两角，右边贴屏，从右侧滑出
        int radius = dp(28);
        panel = new SwipeFrame(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(236, 241, 239));
        bg.setCornerRadii(new float[] {radius, radius, 0, 0, 0, 0, radius, radius});
        panel.setBackground(bg);
        panel.setElevation(dp(16));
        panel.setClickable(true);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(radius, dp(26), dp(18), dp(24));
        title = new TextView(context);
        title.setTextSize(24);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(title);
        hint = new TextView(context);
        hint.setTextSize(13);
        hint.setTextColor(MUTED);
        hint.setLineSpacing(dp(3), 1f);
        hint.setPadding(dp(2), dp(8), dp(2), dp(12));
        column.addView(hint);
        grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        column.addView(grid);

        ScrollView scroll = new ScrollView(context);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(panel, new LayoutParams(dp(380), LayoutParams.MATCH_PARENT, Gravity.END));
        setVisibility(GONE);
    }

    /**
     * 测量前定宽度：平板上最宽 380dp、不超过屏幕的 60%；窄屏上至少 300dp，但不超过屏幕的 90%。
     * 在测量里直接改，第一次打开就生效，不用等下一轮布局。
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        if (w > 0) {
            int narrow = Math.min(dp(300), Math.round(w * 0.9f));
            int width = Math.min(dp(380), Math.max(Math.round(w * 0.6f), narrow));
            LayoutParams lp = (LayoutParams) panel.getLayoutParams();
            if (lp.width != width) lp.width = width;
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /** 应用栏是否打开。 */
    boolean isOpen() {
        return open;
    }

    /** 按当前语言和应用列表填好内容，从右侧滑入。 */
    void show(List<String> packages) {
        if (open) return;
        open = true;
        fill(packages);
        setVisibility(VISIBLE);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(200).start();
        panel.post(() -> {
            panel.setTranslationX(panel.getWidth());
            panel.animate().translationX(0).setDuration(220).start();
        });
    }

    /** 应用列表变化时，已打开的应用栏立即更新；列表空了就收起。 */
    void refresh(List<String> packages) {
        if (!open) return;
        if (packages.isEmpty()) hide();
        else fill(packages);
    }

    /** 滑出到右侧后隐藏。 */
    void hide() {
        if (!open) return;
        open = false;
        scrim.animate().alpha(0f).setDuration(180).start();
        panel.animate().translationX(panel.getWidth()).setDuration(200).withEndAction(this::finishHide).start();
    }

    /** 打开外部应用前立即收起，不等动画，免得回来时还看见半截应用栏。 */
    void hideNow() {
        if (!open) return;
        open = false;
        scrim.animate().cancel();
        panel.animate().cancel();
        finishHide();
    }

    /** 收起完成。 */
    private void finishHide() {
        setVisibility(GONE);
        host.onAppDrawerClosed();
    }

    /** 文案和主界面一样沿用这次启动时的语言；卸载掉的应用不显示。 */
    private void fill(List<String> packages) {
        Context ui = getContext();
        title.setText(ui.getString(R.string.apps_title));
        hint.setText(ui.getString(R.string.apps_drawer_hint));
        grid.removeAllViews();
        List<AppLauncher.Entry> entries = new ArrayList<>();
        for (String pkg : packages) {
            AppLauncher.Entry entry = AppLauncher.describe(getContext(), pkg);
            if (entry != null) entries.add(entry);
        }
        if (entries.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText(ui.getString(R.string.apps_drawer_empty));
            empty.setTextSize(15);
            empty.setTextColor(TEXT);
            empty.setPadding(dp(2), dp(12), dp(2), dp(12));
            grid.addView(empty);
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < entries.size(); i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.topMargin = dp(10);
                grid.addView(row, rowLp);
            }
            row.addView(tile(entries.get(i)), tileParams());
        }
        // 最后一行补空位，图标宽度和上面一致
        int rest = entries.size() % COLUMNS;
        if (rest != 0 && row != null) {
            for (int i = rest; i < COLUMNS; i++) row.addView(new View(getContext()), tileParams());
        }
    }

    /** 一个应用格子：图标在上，名称在下，白色圆角底。 */
    private View tile(AppLauncher.Entry entry) {
        LinearLayout tile = new LinearLayout(getContext());
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(6), dp(14), dp(6), dp(12));
        // 按下时底色变深，给出点中的反馈
        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(dp(18));
        normal.setColor(Color.WHITE);
        GradientDrawable pressed = new GradientDrawable();
        pressed.setCornerRadius(dp(18));
        pressed.setColor(Color.rgb(214, 228, 226));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_pressed}, pressed);
        bg.addState(new int[] {}, normal);
        tile.setBackground(bg);
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setContentDescription(entry.label);
        ImageView icon = new ImageView(getContext());
        icon.setImageDrawable(entry.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView label = new TextView(getContext());
        label.setText(entry.label);
        label.setTextSize(13);
        label.setTextColor(TEXT);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(8);
        tile.addView(label, labelLp);
        tile.setOnClickListener(v -> host.onAppChosen(entry.packageName));
        return tile;
    }

    /** 三列等宽，中间留缝。 */
    private LinearLayout.LayoutParams tileParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(dp(10));
        return lp;
    }

    /** dp 转像素。 */
    private int dp(int value) {
        return Math.round(value * density);
    }

    /** 面板容器：识别明显向右的横向拖动后收起应用栏。 */
    private final class SwipeFrame extends FrameLayout {
        private final int slop;
        private float downX;
        private float downY;
        private boolean dragging;

        SwipeFrame(Context context) {
            super(context);
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        /** 横向为主且向右超过阈值时接管手势，竖向滚动仍交给列表。 */
        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    dragging = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getRawX() - downX;
                    float dy = ev.getRawY() - downY;
                    if (dx > slop * 2 && dx > Math.abs(dy) * 1.5f) {
                        dragging = true;
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        /** 跟手右移，松手时超过三分之一宽度就收起，否则弹回。 */
        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            float dx = ev.getRawX() - downX;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) setTranslationX(Math.max(0, dx));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging && dx > getWidth() / 3f) hide();
                    else animate().translationX(0).setDuration(160).start();
                    dragging = false;
                    return true;
                default:
                    return true;
            }
        }
    }
}
