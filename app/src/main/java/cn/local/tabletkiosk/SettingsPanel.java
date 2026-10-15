package cn.local.tabletkiosk;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 从左侧滑入的设置面板：点击外侧或向左滑回关闭，保存后立即生效。 */
final class SettingsPanel extends FrameLayout {
    /** 面板需要主界面提供的操作。 */
    interface Host {
        void reloadNow();

        void goHome();

        void requestCameraPermission();

        void requestBatteryExemption();

        void requestOverlayPermission();

        void openSystemSettings();

        void onPanelClosed();

        boolean cameraGranted();

        boolean batteryExempt();

        boolean overlayGranted();
    }

    private static final int TEXT = Color.rgb(24, 48, 57);
    private static final int MUTED = Color.rgb(78, 98, 104);
    private static final int ACCENT = Color.rgb(47, 93, 98);

    private final KioskHub hub;
    private final Host host;
    private final float density;
    private final View scrim;
    private final PanelFrame panel;
    private final ScrollView scroll;
    private final LinearLayout form;
    private final Map<String, EditText> fields = new HashMap<>();
    private Switch motionSwitch;
    private Switch mqttSwitch;
    private Switch lanSwitch;
    private Switch lockSwitch;
    private TextView statusText;
    private TextView lanHint;
    private TextView ownerText;
    private Button cameraButton;
    private Button batteryButton;
    private Button overlayButton;
    private boolean open;

    /** 构建面板视图，初始隐藏。 */
    SettingsPanel(Context context, KioskHub hub, Host host) {
        super(context);
        this.hub = hub;
        this.host = host;
        density = getResources().getDisplayMetrics().density;

        // 半透明遮罩：点击外侧关闭
        scrim = new View(context);
        scrim.setBackgroundColor(Color.argb(110, 0, 0, 0));
        scrim.setOnClickListener(v -> hide());
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        panel = new PanelFrame(context);
        panel.setBackgroundColor(Color.rgb(246, 249, 248));
        panel.setElevation(dp(12));
        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(form, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(panel, new LayoutParams(dp(440), LayoutParams.MATCH_PARENT, Gravity.START));

        buildForm(context);
        setVisibility(GONE);
    }

    /** 屏幕尺寸变化时，面板宽度不超过 440dp 和屏幕的 90%。 */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        LayoutParams lp = (LayoutParams) panel.getLayoutParams();
        int width = Math.min(dp(440), Math.round(w * 0.9f));
        if (lp.width != width) {
            lp.width = width;
            panel.setLayoutParams(lp);
        }
    }

    /** 面板是否打开。 */
    boolean isOpen() {
        return open;
    }

    /** 填入当前配置并从左侧滑入。 */
    void show() {
        if (open) return;
        open = true;
        populate(hub.config());
        refreshStatus();
        setVisibility(VISIBLE);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(200).start();
        panel.post(() -> {
            panel.setTranslationX(-panel.getWidth());
            panel.animate().translationX(0).setDuration(220).start();
        });
    }

    /** 收起键盘并滑出到左侧。 */
    void hide() {
        if (!open) return;
        open = false;
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
        scrim.animate().alpha(0f).setDuration(180).start();
        panel.animate().translationX(-panel.getWidth()).setDuration(200).withEndAction(() -> {
            setVisibility(GONE);
            host.onPanelClosed();
        }).start();
    }

    /** 刷新面板顶部状态、设备所有者说明和权限按钮。 */
    void refreshStatus() {
        if (statusText == null) return;
        KioskStatus s = hub.status();
        KioskConfig c = hub.config();
        String battery = s.battery >= 0 ? s.battery + "%（" + chargeLabel(s.chargeState) + "）" : "未知";
        String mqtt = !c.mqttEnabled ? "未开启" : s.mqttConnected ? "已连接" : "未连接";
        statusText.setText("亮屏：" + yes(s.screenOn()) + "　有人靠近：" + yes(s.presence) + "　当前亮度：" + s.brightness
                + "%\n电量：" + battery + "　MQTT：" + mqtt + "\n锁定模式：" + (s.lockTask ? "已开启" : "未开启"));

        String ip = NetUtil.localIpv4(getContext());
        String address = "http://" + (ip.isEmpty() ? "平板IP" : ip) + ":" + c.lanPort;
        lanHint.setText("地址：" + address + "\n请求头带 Authorization: Bearer 你的密码\n"
                + "GET /status　POST /reload　POST /wake\nPOST /brightness，正文写 1 到 100\n"
                + "POST /reboot，需要设备所有者\n示例：curl -X POST -H \"Authorization: Bearer 你的密码\" "
                + address + "/reload");

        ownerText.setText(s.deviceOwner
                ? "已是设备所有者：锁定模式、固定为桌面、自动授予摄像头权限、关闭锁屏和远程重启已启用。"
                : "未设为设备所有者，下面的锁定模式不生效。平板上移除所有账号后，在电脑执行：\n"
                + "adb shell dpm set-device-owner " + getContext().getPackageName() + "/.KioskAdminReceiver\n"
                + "设置后只能恢复出厂设置才能解除。");

        cameraButton.setVisibility(host.cameraGranted() ? GONE : VISIBLE);
        batteryButton.setVisibility(host.batteryExempt() ? GONE : VISIBLE);
        overlayButton.setVisibility(s.deviceOwner || host.overlayGranted() ? GONE : VISIBLE);
    }

    /** 按顺序创建全部设置项。 */
    private void buildForm(Context context) {
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(context, "居家屏幕设置", 22, TEXT, true);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(button(context, "关闭", false, this::hide));
        form.addView(header);

        statusText = text(context, "", 14, MUTED, false);
        statusText.setPadding(0, dp(10), 0, dp(4));
        form.addView(statusText);

        section(context, "页面");
        field(context, ConfigForm.HOME_URL, "首页网址", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field(context, ConfigForm.IDLE_RELOAD_HOURS, "空闲重新加载（小时，0 关闭）", InputType.TYPE_CLASS_NUMBER);
        field(context, ConfigForm.RETRY_SECONDS, "加载失败重试（秒，0 关闭）", InputType.TYPE_CLASS_NUMBER);
        LinearLayout pageActions = row(context);
        pageActions.addView(button(context, "立即刷新", false, host::reloadNow), weighted());
        pageActions.addView(button(context, "回到首页", false, host::goHome), weighted());
        form.addView(pageActions);

        section(context, "亮度与人体检测");
        field(context, ConfigForm.WATCH_BRIGHTNESS, "观看亮度（%）", InputType.TYPE_CLASS_NUMBER);
        field(context, ConfigForm.STANDBY_BRIGHTNESS, "待机亮度（%，0 最暗）", InputType.TYPE_CLASS_NUMBER);
        field(context, ConfigForm.DIM_DELAY_SECONDS, "无人后降亮度（秒，0 始终观看亮度）", InputType.TYPE_CLASS_NUMBER);
        motionSwitch = toggle(context, "人体检测（前置摄像头，画面不保存不上传）");
        field(context, ConfigForm.MOTION_SENSITIVITY, "动作灵敏度（0 到 100，0 关闭）", InputType.TYPE_CLASS_NUMBER);
        field(context, ConfigForm.PRESENCE_HOLD_SECONDS, "有人状态保持（秒）", InputType.TYPE_CLASS_NUMBER);
        cameraButton = button(context, "授予摄像头权限", false, host::requestCameraPermission);
        form.addView(cameraButton, fullWidth());

        section(context, "MQTT");
        mqttSwitch = toggle(context, "开启 MQTT，并注册到 Home Assistant");
        field(context, ConfigForm.MQTT_HOST, "主机", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field(context, ConfigForm.MQTT_PORT, "端口", InputType.TYPE_CLASS_NUMBER);
        field(context, ConfigForm.MQTT_USERNAME, "用户名", InputType.TYPE_CLASS_TEXT);
        field(context, ConfigForm.MQTT_PASSWORD, "密码",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field(context, ConfigForm.MQTT_BASE_TOPIC, "基础主题", InputType.TYPE_CLASS_TEXT);
        field(context, ConfigForm.MQTT_DISCOVERY_PREFIX, "发现前缀", InputType.TYPE_CLASS_TEXT);
        field(context, ConfigForm.MQTT_STATE_INTERVAL, "状态上报间隔（秒，0 只在变化时上报）", InputType.TYPE_CLASS_NUMBER);

        section(context, "局域网管理");
        lanSwitch = toggle(context, "开启局域网管理接口");
        field(context, ConfigForm.LAN_PORT, "端口", InputType.TYPE_CLASS_NUMBER);
        field(context, ConfigForm.LAN_PASSWORD, "管理密码（至少 " + ConfigForm.MIN_LAN_PASSWORD_LENGTH + " 位）",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        lanHint = text(context, "", 13, MUTED, false);
        lanHint.setTextIsSelectable(true);
        form.addView(lanHint);

        section(context, "设备所有者");
        ownerText = text(context, "", 13, MUTED, false);
        ownerText.setTextIsSelectable(true);
        form.addView(ownerText);
        lockSwitch = toggle(context, "锁定模式（屏蔽 Home、多任务和下拉状态栏）");

        section(context, "系统");
        batteryButton = button(context, "加入电池优化白名单", false, host::requestBatteryExemption);
        form.addView(batteryButton, fullWidth());
        overlayButton = button(context, "允许开机自动打开", false, host::requestOverlayPermission);
        form.addView(overlayButton, fullWidth());
        form.addView(button(context, "打开系统设置", false, host::openSystemSettings), fullWidth());

        LinearLayout actions = row(context);
        actions.setPadding(0, dp(20), 0, 0);
        actions.addView(button(context, "保存", true, this::save), weighted());
        actions.addView(button(context, "取消", false, this::hide), weighted());
        form.addView(actions);
    }

    /** 把配置写入各输入框和开关，并清除旧的错误提示。 */
    private void populate(KioskConfig c) {
        Map<String, String> text = ConfigForm.toText(c);
        for (Map.Entry<String, EditText> e : fields.entrySet()) {
            e.getValue().setText(text.get(e.getKey()));
            e.getValue().setError(null);
        }
        motionSwitch.setChecked(c.motionEnabled);
        mqttSwitch.setChecked(c.mqttEnabled);
        lanSwitch.setChecked(c.lanEnabled);
        lockSwitch.setChecked(c.lockTaskEnabled);
    }

    /** 校验并保存；有错误时标在对应输入框并滚动到第一处。 */
    private void save() {
        Map<String, String> text = new HashMap<>();
        for (Map.Entry<String, EditText> e : fields.entrySet()) {
            text.put(e.getKey(), e.getValue().getText().toString());
            e.getValue().setError(null);
        }
        ConfigForm.Result result = ConfigForm.parse(text, motionSwitch.isChecked(), mqttSwitch.isChecked(),
                lanSwitch.isChecked(), lockSwitch.isChecked());
        if (!result.ok()) {
            EditText first = null;
            for (Map.Entry<String, String> e : result.errors.entrySet()) {
                EditText input = fields.get(e.getKey());
                if (input == null) continue;
                input.setError(e.getValue());
                if (first == null) first = input;
            }
            if (first != null) {
                EditText target = first;
                scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, target.getTop() - dp(40))));
                target.requestFocus();
            }
            Toast.makeText(getContext(), "有 " + result.errors.size() + " 项需要修改", Toast.LENGTH_SHORT).show();
            return;
        }
        hub.updateConfig(result.config);
        refreshStatus();
        Toast.makeText(getContext(), "已保存并生效", Toast.LENGTH_SHORT).show();
    }

    /** 添加分组标题。 */
    private void section(Context context, String title) {
        TextView t = text(context, title, 17, ACCENT, true);
        t.setPadding(0, dp(22), 0, dp(6));
        form.addView(t);
    }

    /** 添加带标题的输入框。 */
    private void field(Context context, String key, String label, int inputType) {
        TextView l = text(context, label, 14, TEXT, false);
        l.setPadding(0, dp(8), 0, dp(2));
        form.addView(l);
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setTextSize(16);
        input.setTextColor(TEXT);
        form.addView(input, fullWidth());
        fields.put(key, input);
    }

    /** 添加开关行。 */
    private Switch toggle(Context context, String label) {
        Switch s = new Switch(context);
        s.setText(label);
        s.setTextSize(15);
        s.setTextColor(TEXT);
        s.setPadding(0, dp(10), 0, dp(10));
        form.addView(s, fullWidth());
        return s;
    }

    /** 创建按钮；主按钮使用强调色。 */
    private Button button(Context context, String label, boolean primary, Runnable action) {
        Button b = new Button(context);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setMinHeight(dp(48));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        if (primary) {
            bg.setColor(ACCENT);
            b.setTextColor(Color.WHITE);
        } else {
            bg.setColor(Color.WHITE);
            bg.setStroke(dp(1), Color.rgb(166, 190, 180));
            b.setTextColor(TEXT);
        }
        b.setBackground(bg);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    /** 创建文字视图。 */
    private static TextView text(Context context, String value, int sp, int color, boolean bold) {
        TextView t = new TextView(context);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** 创建横向按钮行。 */
    private LinearLayout row(Context context) {
        LinearLayout r = new LinearLayout(context);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(10), 0, 0);
        return r;
    }

    /** 横向等分布局参数。 */
    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(dp(8));
        return lp;
    }

    /** 整行宽度布局参数。 */
    private LinearLayout.LayoutParams fullWidth() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        return lp;
    }

    /** dp 转像素。 */
    private int dp(int value) {
        return Math.round(value * density);
    }

    /** 布尔值转中文。 */
    private static String yes(boolean value) {
        return value ? "是" : "否";
    }

    /** 充电状态转中文。 */
    private static String chargeLabel(String state) {
        switch (state) {
            case BatteryState.CHARGING:
                return "充电中";
            case BatteryState.DISCHARGING:
                return "未充电";
            case BatteryState.NOT_CHARGING:
                return "已插电未充电";
            case BatteryState.FULL:
                return "已充满";
            default:
                return state.toLowerCase(Locale.ROOT);
        }
    }

    /** 面板本体：横向向左拖动超过阈值时关闭，纵向滚动交给内部滚动视图。 */
    private final class PanelFrame extends FrameLayout {
        private final int slop;
        private float downX;
        private float downY;
        private boolean dragging;

        /** 读取系统触摸阈值。 */
        PanelFrame(Context context) {
            super(context);
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        /** 识别明显向左的横向拖动后接管手势。 */
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
                    if (dx < -slop * 2 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        dragging = true;
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        /** 跟随手指左移，松手时按距离决定关闭或弹回。 */
        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            float dx = ev.getRawX() - downX;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    // 按在面板空白处时不会经过拦截判断，这里补判一次
                    if (!dragging && dx < -slop * 2 && Math.abs(dx) > Math.abs(ev.getRawY() - downY) * 1.5f) {
                        dragging = true;
                    }
                    if (dragging) setTranslationX(Math.min(0, dx));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging && dx < -dp(80)) hide();
                    else animate().translationX(0).setDuration(150).start();
                    dragging = false;
                    return true;
                default:
                    return true;
            }
        }
    }
}
