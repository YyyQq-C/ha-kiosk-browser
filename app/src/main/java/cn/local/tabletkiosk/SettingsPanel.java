package cn.local.tabletkiosk;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.UnderlineSpan;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** 从左侧滑入的设置面板：改完一项就保存，点外侧或向左滑关闭。 */
final class SettingsPanel extends FrameLayout {
    /** 面板需要主界面提供的操作。 */
    interface Host {
        void reloadNow();

        void goHome();

        void requestCameraPermission();

        void requestBatteryExemption();

        void requestOverlayPermission();

        void openSystemSettings();

        /** 退出锁定模式并打开其他应用。 */
        void releaseKiosk();

        void onPanelClosed();

        boolean cameraGranted();

        boolean batteryExempt();

        boolean overlayGranted();

        void requestWifiPermission();

        boolean wifiGranted();

        /** 请求麦克风，供常驻唤醒和点按说话使用。 */
        void requestMicPermission();

        /** 是否已经允许使用麦克风。 */
        boolean micGranted();

        /** 记下语言。设置面板自行更换文案，不重建 Activity。 */
        void onLanguageChanged(String language);

        /** 把设置写到用户选择的文件。 */
        void exportSettings();

        /** 从用户选择的文件读回设置。 */
        void importSettings();

        /** 首次设置已确认，可以加载首页。 */
        void onSetupFinished();

        /** 手动检查 GitHub 上有没有新的正式包。 */
        void checkForUpdate();
    }

    private static final int TEXT = Color.rgb(22, 40, 44);
    private static final int MUTED = Color.rgb(96, 116, 120);
    private static final int FOOTER = Color.rgb(150, 164, 166);
    private static final int ACCENT = Color.rgb(32, 92, 96);
    private static final int FIELD = Color.rgb(244, 247, 246);
    private static final int LINE = Color.rgb(214, 224, 222);
    private static final int OK_COLOR = Color.rgb(30, 122, 92);
    private static final int FAIL_COLOR = Color.rgb(176, 72, 64);
    /** MQTT 卡片上的输入项。这些值只在点「保存并同步」时写入。 */
    private static final String[] MQTT_KEYS = {
            ConfigForm.MQTT_HOST,
            ConfigForm.MQTT_PORT,
            ConfigForm.MQTT_USERNAME,
            ConfigForm.MQTT_PASSWORD,
            ConfigForm.MQTT_BASE_TOPIC,
            ConfigForm.MQTT_DISCOVERY_PREFIX,
            ConfigForm.MQTT_STATE_INTERVAL
    };

    private final KioskHub hub;
    private final Host host;
    private final float density;
    private final View scrim;
    private final PanelFrame panel;
    private final StableScroll scroll;
    private final LinearLayout form;
    private final Map<String, EditText> fields = new HashMap<>();
    /** 百分比拖动条。保存时写回文本，避免这些项从输入框地图消失后被当成缺省。 */
    private final Map<String, PercentBar> percents = new HashMap<>();
    /** 0 到 23 点。表单只显示当前小时；保存时写回，避免这些项从输入框地图消失后被重置成 0。 */
    private final Map<String, HourField> hours = new HashMap<>();
    private Switch motionSwitch;
    private Switch scheduleSwitch;
    private Switch dailySwitch;
    private Switch sleepSwitch;
    private Switch screensaverSwitch;
    private Switch screensaverWeatherSwitch;
    private Switch screensaverLunarSwitch;
    private Switch mqttSwitch;
    private TextView mqttResult;
    /** 正在等这一次保存的连接结果，完成后才弹出成功或失败。 */
    private boolean awaitingMqtt;
    private final Consumer<KioskHub.MqttLink> mqttLinkListener = this::onMqttLink;
    private Switch lanSwitch;
    private Switch lockSwitch;
    private Switch assistSwitch;
    private Button micButton;
    private LinearLayout statusGrid;
    private LinearLayout statRow;
    private int statCount;
    /** 状态数字，刷新时只改文字，避免拆掉视图后滚动跳回顶部。 */
    private final List<TextView> statValues = new ArrayList<>();
    private TextView lanHint;
    private TextView ownerText;
    private Button cameraButton;
    private Button batteryButton;
    private Button overlayButton;
    private Button wifiButton;
    private Button closeButton;
    private Button startButton;
    private TextView setupHint;
    /** 首次打开时必须先确认首页，不能点外侧关掉。 */
    private boolean setup;
    private LinearLayout currentCard;
    private LinearLayout pinCurrentRow;
    private EditText pinCurrent;
    private EditText pinNew;
    private EditText pinConfirm;
    /** 卡片说明。挂在 Activity 的 Dialog 窗口上，不放进会被圆角裁剪的设置抽屉。 */
    private Dialog helpDialog;
    /** 设置文案使用的 Context。切换语言时换成对应 Locale，不改 Activity。 */
    private final Context panelBase;
    private Context ui;
    /** 面板当前文案语言。再点同一种语言不重绘。 */
    private String shownLanguage;
    private boolean open;
    private boolean filling;
    /** 点小时后弹出的选择框。收起面板时关掉，未确认的滚动不写回。 */
    private AlertDialog hourDialog;

    /** 构建面板视图，初始隐藏。 */
    SettingsPanel(Context context, KioskHub hub, Host host) {
        super(context);
        this.hub = hub;
        this.host = host;
        panelBase = context;
        ui = context;
        shownLanguage = AppLocales.effective(context);
        density = getResources().getDisplayMetrics().density;

        // 半透明遮罩：点击外侧关闭
        scrim = new View(context);
        scrim.setBackgroundColor(Color.argb(110, 0, 0, 0));
        scrim.setOnClickListener(v -> hide());
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // 只圆右侧两角。左边贴屏，从左侧滑出。
        int radius = dp(28);
        panel = new PanelFrame(context, radius);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(Color.rgb(236, 241, 239));
        panelBg.setCornerRadii(new float[] {0, 0, radius, radius, radius, radius, 0, 0});
        panel.setBackground(panelBg);
        panel.setElevation(dp(16));
        scroll = new StableScroll(context);
        scroll.setFillViewport(true);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        // 右侧留出圆角加空隙，顶部下移，关闭按钮和卡片停在圆弧内侧，不压在弧线上。
        int gutter = dp(18);
        form.setPadding(gutter, gutter + dp(8), radius + dp(16), dp(36));
        scroll.addView(form, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(panel, new LayoutParams(dp(600), LayoutParams.MATCH_PARENT, Gravity.START));

        buildForm(context);
        hub.addMqttLinkListener(mqttLinkListener);
        setVisibility(GONE);
    }

    /** 屏幕尺寸变化时，面板最宽 600dp，并且不超过屏幕的 72%。 */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        LayoutParams lp = (LayoutParams) panel.getLayoutParams();
        int width = Math.min(dp(600), Math.round(w * 0.72f));
        if (lp.width != width) {
            lp.width = width;
            panel.setLayoutParams(lp);
        }
    }

    /** 面板是否打开。 */
    boolean isOpen() {
        return open;
    }

    /** 首次启动打开设置。首页留空，确认前不能关掉。 */
    void beginSetup() {
        setup = true;
        show();
        EditText home = fields.get(ConfigForm.HOME_URL);
        if (home != null) {
            home.setText("");
            home.setHint(ui.getString(R.string.setup_url_hint));
        }
        applySetupChrome();
    }

    /** 导入或其他路径已经有首页时，解除首次设置限制。 */
    void exitSetup() {
        setup = false;
        applySetupChrome();
    }

    /** 是否还停在首次设置。 */
    boolean isSetup() {
        return setup;
    }

    /** 按是否首次设置显示「开始使用」或「关闭」。 */
    private void applySetupChrome() {
        if (closeButton != null) closeButton.setVisibility(setup ? GONE : VISIBLE);
        if (setupHint != null) setupHint.setVisibility(setup ? VISIBLE : GONE);
        if (startButton != null) startButton.setVisibility(setup ? VISIBLE : GONE);
        if (setup) {
            EditText home = fields.get(ConfigForm.HOME_URL);
            if (home != null && home.getHint() == null) home.setHint(ui.getString(R.string.setup_url_hint));
        }
    }

    /** 校验首页并结束首次设置。地址无效时留在设置里。 */
    private void finishSetup() {
        commit();
        EditText home = fields.get(ConfigForm.HOME_URL);
            if (home != null && home.getError() != null) {
            scroll.focusField(home);
            return;
        }
        String url = home == null || home.getText() == null ? "" : home.getText().toString().trim();
        if (!(url.startsWith("http://") || url.startsWith("https://")) || url.length() < 10 || url.contains(" ")) {
            if (home != null) {
                home.setError(ui.getString(R.string.error_url));
                scroll.focusField(home);
            }
            return;
        }
        hub.store().markSetupDone();
        setup = false;
        applySetupChrome();
        host.onSetupFinished();
        hide();
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
            // 焦点留在滚动区域，避免第一个输入框弹出绿色光标
            scroll.requestFocus();
        });
    }

    /** 收起键盘并滑出到左侧。首次设置未确认时不收起。 */
    void hide() {
        if (!open || setup) return;
        dismissHelp();
        // 未点确认的小时选择直接丢掉，随后 commit 仍用表单上的当前小时
        if (hourDialog != null) hourDialog.dismiss();
        open = false;
        commit();
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
        scrim.animate().alpha(0f).setDuration(180).start();
        panel.animate().translationX(-panel.getWidth()).setDuration(200).withEndAction(() -> {
            setVisibility(GONE);
            host.onPanelClosed();
        }).start();
    }

    /** 面板从窗口移除时关掉说明，避免对话框留在已经销毁的界面上。 */
    @Override
    protected void onDetachedFromWindow() {
        dismissHelp();
        if (hourDialog != null) hourDialog.dismiss();
        hub.removeMqttLinkListener(mqttLinkListener);
        super.onDetachedFromWindow();
    }

    /** 刷新面板顶部状态、设备所有者说明和权限按钮。 */
    void refreshStatus() {
        if (statusGrid == null) return;
        // 改文字和按钮显隐会触发布局。先钉住位置，被夹走时再写回。
        scroll.keepPosition();
        KioskStatus s = hub.status();
        KioskConfig c = hub.config();
        String battery = s.battery >= 0
                ? ui.getString(R.string.battery_value, s.battery, chargeLabel(s.chargeState))
                : ui.getString(R.string.unknown);
        String mqtt = !c.mqttEnabled ? ui.getString(R.string.mqtt_off)
                : s.mqttConnected ? ui.getString(R.string.mqtt_connected)
                : ui.getString(R.string.mqtt_disconnected);
        String[] stats = {
                yes(s.screenOn()),
                yes(s.presence),
                s.brightness + "%",
                battery,
                mqtt,
                s.lockTask ? ui.getString(R.string.lock_on) : ui.getString(R.string.lock_off),
                percentText(s.cpuPercent),
                memoryText(s),
                percentText(s.storagePercent),
                temperatureText(s.batteryTempTenths),
                megabyteText(s.appMemoryMb)
        };
        // 格数不变时只改数字。拆掉再加会让滚动范围瞬间变短，位置被夹回顶部。
        if (statValues.size() == stats.length) {
            for (int i = 0; i < stats.length; i++) statValues.get(i).setText(stats[i]);
        } else {
            statusGrid.removeAllViews();
            statRow = null;
            statCount = 0;
            statValues.clear();
            addStat(ui.getString(R.string.entity_screen_on), stats[0]);
            addStat(ui.getString(R.string.entity_presence), stats[1]);
            addStat(ui.getString(R.string.entity_brightness), stats[2]);
            addStat(ui.getString(R.string.entity_battery), stats[3]);
            addStat(ui.getString(R.string.section_mqtt), stats[4]);
            addStat(ui.getString(R.string.entity_lock), stats[5]);
            addStat(ui.getString(R.string.entity_cpu), stats[6]);
            addStat(ui.getString(R.string.entity_memory), stats[7]);
            addStat(ui.getString(R.string.entity_storage), stats[8]);
            addStat(ui.getString(R.string.entity_temperature), stats[9]);
            addStat(ui.getString(R.string.entity_app_memory), stats[10]);
        }

        String ip = NetUtil.localIpv4(getContext());
        String address = "http://" + (ip.isEmpty() ? ui.getString(R.string.tablet_ip) : ip) + ":" + c.lanPort;
        lanHint.setText(ui.getString(R.string.lan_hint, address));

        String command = "adb shell dpm set-device-owner " + getContext().getPackageName() + "/.KioskAdminReceiver";
        ownerText.setText(s.deviceOwner
                ? ui.getString(R.string.owner_active)
                : ui.getString(R.string.owner_inactive, command));

        cameraButton.setVisibility(host.cameraGranted() ? GONE : VISIBLE);
        batteryButton.setVisibility(host.batteryExempt() ? GONE : VISIBLE);
        overlayButton.setVisibility(s.deviceOwner || host.overlayGranted() ? GONE : VISIBLE);
        wifiButton.setVisibility(host.wifiGranted() ? GONE : VISIBLE);
        if (micButton != null) {
            micButton.setVisibility(hub.config().assistEnabled && !host.micGranted() ? VISIBLE : GONE);
        }
        paintMqttLink(hub.mqttLink());
    }

    /** 按顺序创建全部设置项。 */
    private void buildForm(Context context) {
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(context, context.getString(R.string.settings_title), 24, TEXT, true);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(languageButton(context));
        closeButton = button(context, context.getString(R.string.close), this::hide, false);
        header.addView(closeButton);
        form.addView(header);

        TextView openHint = text(context, context.getString(R.string.settings_open_hint), 13, MUTED, false);
        openHint.setLineSpacing(dp(3), 1f);
        openHint.setPadding(dp(2), dp(8), dp(2), dp(4));
        form.addView(openHint, fullWidth());

        statusGrid = new LinearLayout(context);
        statusGrid.setOrientation(LinearLayout.VERTICAL);
        statValues.clear();
        statRow = null;
        statCount = 0;
        card(context).addView(statusGrid);

        section(context, context.getString(R.string.section_page), R.string.help_page);
        field(context, ConfigForm.HOME_URL, context.getString(R.string.home_url),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        setupHint = text(context, context.getString(R.string.setup_hint), 14, MUTED, false);
        setupHint.setLineSpacing(dp(3), 1f);
        setupHint.setVisibility(GONE);
        currentCard.addView(setupHint, fullWidth());
        startButton = button(context, context.getString(R.string.setup_start), this::finishSetup, true);
        startButton.setVisibility(GONE);
        currentCard.addView(startButton, fullWidth());
        currentCard.addView(button(context, context.getString(R.string.find_ha), this::findHa, false), fullWidth());
        pair(context,
                ConfigForm.IDLE_RELOAD_HOURS, context.getString(R.string.idle_reload), InputType.TYPE_CLASS_NUMBER,
                ConfigForm.RETRY_SECONDS, context.getString(R.string.retry_seconds), InputType.TYPE_CLASS_NUMBER);
        field(context, ConfigForm.WATCHDOG_SECONDS, context.getString(R.string.watchdog_seconds), InputType.TYPE_CLASS_NUMBER);
        dailySwitch = toggle(context, context.getString(R.string.daily_switch));
        // 每天刷新的小时只能选 0 到 23；分钟仍可输入
        placePair(context,
                hourColumn(context, ConfigForm.DAILY_RELOAD_HOUR, context.getString(R.string.daily_hour)),
                fieldColumn(context, ConfigForm.DAILY_RELOAD_MINUTE, context.getString(R.string.daily_minute),
                        InputType.TYPE_CLASS_NUMBER));
        LinearLayout pageActions = row(context);
        pageActions.addView(button(context, context.getString(R.string.reload_now), host::reloadNow, true), weighted());
        pageActions.addView(button(context, context.getString(R.string.go_home), host::goHome, false), weighted());
        currentCard.addView(pageActions);
        currentCard.addView(button(context, context.getString(R.string.pick_dashboard), this::chooseDashboard, false),
                fullWidth());

        section(context, context.getString(R.string.section_brightness), R.string.help_brightness);
        // 观看 1–100、待机 0–100，与 ConfigForm 校验一致
        placePair(context,
                percentColumn(context, ConfigForm.WATCH_BRIGHTNESS, context.getString(R.string.watch_brightness), 1, 100),
                percentColumn(context, ConfigForm.STANDBY_BRIGHTNESS, context.getString(R.string.standby_brightness), 0, 100));
        field(context, ConfigForm.DIM_DELAY_SECONDS, context.getString(R.string.dim_delay), InputType.TYPE_CLASS_NUMBER);
        // 屏保开关、天气、农历和屏保亮度紧挨待机亮度与变暗延迟；关掉屏保后这些项仍可改并保存
        screensaverSwitch = toggle(context, context.getString(R.string.screensaver_switch));
        screensaverWeatherSwitch = toggle(context, context.getString(R.string.screensaver_weather));
        screensaverLunarSwitch = toggle(context, context.getString(R.string.screensaver_lunar));
        percent(context, ConfigForm.SCREENSAVER_BRIGHTNESS, context.getString(R.string.screensaver_brightness), 0, 100);
        scheduleSwitch = toggle(context, context.getString(R.string.schedule_switch));
        placePair(context,
                hourColumn(context, ConfigForm.DAY_START_HOUR, context.getString(R.string.day_start)),
                hourColumn(context, ConfigForm.NIGHT_START_HOUR, context.getString(R.string.night_start)));
        // 白天、夜间亮度都是 1–100
        placePair(context,
                percentColumn(context, ConfigForm.DAY_BRIGHTNESS, context.getString(R.string.day_brightness), 1, 100),
                percentColumn(context, ConfigForm.NIGHT_BRIGHTNESS, context.getString(R.string.night_brightness), 1, 100));
        sleepSwitch = toggle(context, context.getString(R.string.sleep_switch));
        placePair(context,
                hourColumn(context, ConfigForm.SLEEP_START_HOUR, context.getString(R.string.sleep_start)),
                hourColumn(context, ConfigForm.SLEEP_END_HOUR, context.getString(R.string.sleep_end)));
        motionSwitch = toggle(context, context.getString(R.string.motion_switch));
        // 灵敏度 0–100 用拖动条；有人保持仍是数字输入
        placePair(context,
                percentColumn(context, ConfigForm.MOTION_SENSITIVITY, context.getString(R.string.motion_sensitivity), 0, 100),
                fieldColumn(context, ConfigForm.PRESENCE_HOLD_SECONDS, context.getString(R.string.presence_hold),
                        InputType.TYPE_CLASS_NUMBER));
        cameraButton = button(context, context.getString(R.string.grant_camera), host::requestCameraPermission, false);
        currentCard.addView(cameraButton, fullWidth());

        section(context, context.getString(R.string.section_sound), R.string.help_sound);
        // 媒体音量 0–100
        percent(context, ConfigForm.VOLUME, context.getString(R.string.volume), 0, 100);

        section(context, context.getString(R.string.section_assist), R.string.help_assist);
        assistSwitch = toggle(context, context.getString(R.string.assist_switch));
        percent(context, ConfigForm.WAKE_SENSITIVITY, context.getString(R.string.wake_sensitivity), 0, 100);
        field(context, ConfigForm.SPEECH_WAIT_SECONDS, context.getString(R.string.speech_wait_seconds),
                InputType.TYPE_CLASS_NUMBER);
        TextView assistHint = text(context, context.getString(R.string.assist_hint), 13, MUTED, false);
        assistHint.setPadding(dp(2), dp(4), dp(2), dp(8));
        currentCard.addView(assistHint, fullWidth());
        micButton = button(context, context.getString(R.string.grant_mic), host::requestMicPermission, false);
        currentCard.addView(micButton, fullWidth());

        section(context, context.getString(R.string.section_mqtt), R.string.help_mqtt);
        // MQTT 不随开关或失焦写入，只有点保存才连接并上报
        mqttSwitch = toggle(context, context.getString(R.string.mqtt_switch), false);
        field(context, ConfigForm.MQTT_HOST, context.getString(R.string.mqtt_host),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        pair(context,
                ConfigForm.MQTT_PORT, context.getString(R.string.mqtt_port), InputType.TYPE_CLASS_NUMBER,
                ConfigForm.MQTT_USERNAME, context.getString(R.string.mqtt_username), InputType.TYPE_CLASS_TEXT, false);
        field(context, ConfigForm.MQTT_PASSWORD, context.getString(R.string.mqtt_password),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, false);
        field(context, ConfigForm.MQTT_BASE_TOPIC, context.getString(R.string.base_topic), InputType.TYPE_CLASS_TEXT, false);
        pair(context,
                ConfigForm.MQTT_DISCOVERY_PREFIX, context.getString(R.string.discovery_prefix), InputType.TYPE_CLASS_TEXT,
                ConfigForm.MQTT_STATE_INTERVAL, context.getString(R.string.state_interval), InputType.TYPE_CLASS_NUMBER, false);
        currentCard.addView(button(context, context.getString(R.string.mqtt_save), this::saveMqtt, true), fullWidth());
        mqttResult = text(context, "", 13, MUTED, false);
        mqttResult.setLineSpacing(dp(3), 1f);
        mqttResult.setTextIsSelectable(true);
        mqttResult.setVisibility(GONE);
        currentCard.addView(mqttResult, fullWidth());

        section(context, context.getString(R.string.section_lan), R.string.help_lan);
        lanSwitch = toggle(context, context.getString(R.string.lan_switch));
        field(context, ConfigForm.LAN_PORT, context.getString(R.string.port), InputType.TYPE_CLASS_NUMBER);
        field(context, ConfigForm.LAN_PASSWORD,
                context.getString(R.string.lan_password, ConfigForm.MIN_LAN_PASSWORD_LENGTH),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        lanHint = text(context, "", 13, MUTED, false);
        lanHint.setLineSpacing(dp(3), 1f);
        lanHint.setTextIsSelectable(true);
        currentCard.addView(lanHint, fullWidth());

        section(context, context.getString(R.string.section_owner), R.string.help_owner);
        ownerText = text(context, "", 13, MUTED, false);
        ownerText.setLineSpacing(dp(3), 1f);
        ownerText.setTextIsSelectable(true);
        currentCard.addView(ownerText, fullWidth());
        lockSwitch = toggle(context, context.getString(R.string.lock_switch));
        currentCard.addView(button(context, context.getString(R.string.release_kiosk), this::releaseKiosk, true),
                fullWidth());

        section(context, context.getString(R.string.section_system), R.string.help_system);
        TextView pinHelp = text(context, context.getString(R.string.settings_pin_help), 13, MUTED, false);
        pinHelp.setLineSpacing(dp(3), 1f);
        currentCard.addView(pinHelp, fullWidth());
        // 设置密码不进自动保存列表，只有点「保存密码」才写入
        pinCurrentRow = pinColumn(context, context.getString(R.string.settings_pin_current));
        pinCurrent = (EditText) pinCurrentRow.getTag();
        currentCard.addView(pinCurrentRow, fullWidth());
        LinearLayout pinNewRow = pinColumn(context, context.getString(R.string.settings_pin_new));
        pinNew = (EditText) pinNewRow.getTag();
        currentCard.addView(pinNewRow, fullWidth());
        LinearLayout pinConfirmRow = pinColumn(context, context.getString(R.string.settings_pin_confirm));
        pinConfirm = (EditText) pinConfirmRow.getTag();
        currentCard.addView(pinConfirmRow, fullWidth());
        currentCard.addView(button(context, context.getString(R.string.settings_pin_save), this::saveSettingsPin, true),
                fullWidth());
        pinCurrentRow.setVisibility(hasSavedPin() ? VISIBLE : GONE);
        batteryButton = button(context, context.getString(R.string.battery_whitelist), host::requestBatteryExemption, false);
        currentCard.addView(batteryButton, fullWidth());
        overlayButton = button(context, context.getString(R.string.allow_boot), host::requestOverlayPermission, false);
        currentCard.addView(overlayButton, fullWidth());
        wifiButton = button(context, context.getString(R.string.grant_wifi), host::requestWifiPermission, false);
        currentCard.addView(wifiButton, fullWidth());
        TextView fileHelp = text(context, context.getString(R.string.settings_file_hint), 13, MUTED, false);
        fileHelp.setLineSpacing(dp(3), 1f);
        currentCard.addView(fileHelp, fullWidth());
        LinearLayout fileActions = row(context);
        fileActions.addView(button(context, context.getString(R.string.export_settings), host::exportSettings, false), weighted());
        fileActions.addView(button(context, context.getString(R.string.import_settings), host::importSettings, false), weighted());
        currentCard.addView(fileActions);
        currentCard.addView(button(context, context.getString(R.string.check_update), host::checkForUpdate, false),
                fullWidth());
        currentCard.addView(button(context, context.getString(R.string.open_system_settings), host::openSystemSettings, false),
                fullWidth());
        addFooter(context);
    }

    /** 在滚动内容末尾放版权和仓库信息，不用分组卡片。 */
    private void addFooter(Context context) {
        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setPadding(dp(12), dp(28), dp(12), dp(4));

        TextView title = text(context, context.getString(R.string.footer_title, BuildConfig.VERSION_NAME), 12, FOOTER, false);
        title.setGravity(Gravity.CENTER);
        footer.addView(title);

        TextView copyright = text(context, context.getString(R.string.footer_copyright), 11, FOOTER, false);
        copyright.setGravity(Gravity.CENTER);
        copyright.setPadding(0, dp(4), 0, 0);
        footer.addView(copyright);

        String email = context.getString(R.string.footer_email);
        String license = context.getString(R.string.footer_license) + context.getString(R.string.footer_contact, email);
        TextView licenseView = text(context, license, 11, FOOTER, false);
        licenseView.setGravity(Gravity.CENTER);
        licenseView.setPadding(0, dp(4), 0, 0);
        licenseView.setLineSpacing(dp(2), 1f);
        bindEmailTap(licenseView, email);
        footer.addView(licenseView);

        TextView github = text(context, context.getString(R.string.footer_github), 11, FOOTER, false);
        github.setGravity(Gravity.CENTER);
        github.setPadding(0, dp(6), 0, 0);
        github.setPaintFlags(github.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        github.setOnClickListener(v -> openGithub(v.getContext()));
        footer.addView(github);

        form.addView(footer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** 邮箱可以长按选择复制；短按点在邮箱上时用邮件应用打开。 */
    private void bindEmailTap(TextView view, String email) {
        String full = view.getText().toString();
        int start = full.indexOf(email);
        if (start >= 0) {
            SpannableString span = new SpannableString(full);
            span.setSpan(new UnderlineSpan(), start, start + email.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            view.setText(span);
        }
        view.setTextIsSelectable(true);
        int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        int longPress = ViewConfiguration.getLongPressTimeout();
        view.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private long downTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        downTime = event.getEventTime();
                        return false;
                    case MotionEvent.ACTION_UP:
                        float dx = event.getX() - downX;
                        float dy = event.getY() - downY;
                        boolean tap = dx * dx + dy * dy <= (float) slop * slop
                                && event.getEventTime() - downTime < longPress;
                        if (!tap) return false;
                        TextView target = (TextView) v;
                        int offset = target.getOffsetForPosition(event.getX(), event.getY());
                        int from = full.indexOf(email);
                        if (from >= 0 && offset >= from && offset <= from + email.length()) {
                            openMailto(v.getContext(), email);
                            return true;
                        }
                        return false;
                    default:
                        return false;
                }
            }
        });
    }

    /** 打开商业授权邮件；没有邮件应用时只记英文日志。 */
    private void openMailto(Context context, String email) {
        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + email));
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            Log.w(KioskApp.LOG, "Failed to open commercial license mailto", e);
        }
    }

    /** 用浏览器打开 GitHub 仓库；打不开时提示，并记英文日志。 */
    private void openGithub(Context context) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.footer_github_url)));
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            Log.w(KioskApp.LOG, "Failed to open GitHub page", e);
            Toast.makeText(context, R.string.toast_github_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    /** 把配置写入各输入框和开关，并清除旧的错误提示。 */
    private void populate(KioskConfig c) {
        filling = true;
        Map<String, String> text = ConfigForm.toText(c);
        for (Map.Entry<String, EditText> e : fields.entrySet()) {
            e.getValue().setText(text.get(e.getKey()));
            clearError(e.getValue());
        }
        for (Map.Entry<String, PercentBar> e : percents.entrySet()) {
            e.getValue().setValue(percentOrMin(text.get(e.getKey()), e.getValue()));
        }
        for (Map.Entry<String, HourField> e : hours.entrySet()) {
            e.getValue().setHour(hourValue(text.get(e.getKey())));
        }
        motionSwitch.setChecked(c.motionEnabled);
        scheduleSwitch.setChecked(c.scheduleEnabled);
        dailySwitch.setChecked(c.dailyReloadEnabled);
        sleepSwitch.setChecked(c.sleepEnabled);
        if (screensaverSwitch != null) screensaverSwitch.setChecked(c.screensaverEnabled);
        if (screensaverWeatherSwitch != null) screensaverWeatherSwitch.setChecked(c.screensaverWeather);
        if (screensaverLunarSwitch != null) screensaverLunarSwitch.setChecked(c.screensaverLunar);
        mqttSwitch.setChecked(c.mqttEnabled);
        lanSwitch.setChecked(c.lanEnabled);
        lockSwitch.setChecked(c.lockTaskEnabled);
        if (assistSwitch != null) assistSwitch.setChecked(c.assistEnabled);
        filling = false;
        resetPinDraft();
    }

    /** 离开输入框或拨动开关时校验并保存；MQTT 草稿不在这里写入。无效内容只标在输入框上。 */
    private void commit() {
        if (filling || motionSwitch == null) return;
        storeForm(false);
    }

    /** 保存 MQTT 草稿，并按新配置立即连接、上报状态。 */
    private void saveMqtt() {
        if (filling || mqttSwitch == null) return;
        storeForm(true);
    }

    /**
     * 校验并保存表单。
     * mqttDraft 为真时只采用 MQTT 卡片上的开关和输入，其余沿用已保存配置；
     * 为假时采用当前表单，但 MQTT 仍用已保存值，避免改别的项时把未点保存的 MQTT 草稿写进去。
     */
    private void storeForm(boolean mqttDraft) {
        // 校验、setError 和配置生效都可能触发布局或把焦点送回顶部输入框。
        scroll.keepPosition();
        KioskConfig saved = hub.config();
        Map<String, String> text = new HashMap<>();
        if (mqttDraft) {
            text.putAll(ConfigForm.toText(saved));
            for (String key : MQTT_KEYS) {
                EditText input = fields.get(key);
                text.put(key, input == null ? "" : input.getText().toString());
                clearError(input);
            }
        } else {
            for (Map.Entry<String, EditText> e : fields.entrySet()) {
                text.put(e.getKey(), e.getValue().getText().toString());
                clearError(e.getValue());
            }
            // 拖动条不在输入框地图里，保存时仍要带上当前整数，避免被校验当成缺省
            for (Map.Entry<String, PercentBar> e : percents.entrySet()) {
                text.put(e.getKey(), e.getValue().text());
            }
            // 小时不在输入框地图里，必须写入当前选中的小时，不能落回默认 0
            for (Map.Entry<String, HourField> e : hours.entrySet()) {
                text.put(e.getKey(), Integer.toString(e.getValue().getHour()));
            }
            Map<String, String> savedText = ConfigForm.toText(saved);
            for (String key : MQTT_KEYS) text.put(key, savedText.get(key));
        }
        boolean mqttEnabled = mqttDraft ? mqttSwitch.isChecked() : saved.mqttEnabled;
        ConfigForm.Result result = ConfigForm.parse(text,
                mqttDraft ? saved.motionEnabled : motionSwitch.isChecked(),
                mqttEnabled,
                mqttDraft ? saved.lanEnabled : lanSwitch.isChecked(),
                mqttDraft ? saved.lockTaskEnabled : lockSwitch.isChecked(),
                mqttDraft ? saved.scheduleEnabled : scheduleSwitch.isChecked(),
                mqttDraft ? saved.dailyReloadEnabled : dailySwitch.isChecked(),
                mqttDraft ? saved.sleepEnabled : sleepSwitch.isChecked(),
                mqttDraft ? saved.assistEnabled : assistSwitch != null && assistSwitch.isChecked(),
                mqttDraft || screensaverSwitch == null ? saved.screensaverEnabled : screensaverSwitch.isChecked(),
                mqttDraft || screensaverWeatherSwitch == null ? saved.screensaverWeather : screensaverWeatherSwitch.isChecked(),
                mqttDraft || screensaverLunarSwitch == null ? saved.screensaverLunar : screensaverLunarSwitch.isChecked());
        if (!result.ok()) {
            for (Map.Entry<String, String> e : result.errors.entrySet()) {
                if (mqttDraft && !isMqttKey(e.getKey())) continue;
                EditText input = fields.get(e.getKey());
                if (input != null) input.setError(errorText(e.getKey(), e.getValue()));
            }
            return;
        }
        // 这次表单没有设置密码字段，必须沿用已保存的密码，不能写成空
        KioskConfig next = ConfigForm.preserveSettingsPin(result.config, saved.settingsPin);
        boolean changed = !next.equals(saved);
        if (mqttDraft) {
            awaitingMqtt = true;
            if (!next.mqttEnabled) {
                if (changed) hub.updateConfig(next);
                hub.reportMqttLink(KioskHub.MqttLink.OFF, "", "");
                refreshStatus();
                return;
            }
            // 先标记由同步任务连接，再保存，避免配置监听和按钮各连一次
            hub.armMqttSync();
            hub.reportMqttLink(KioskHub.MqttLink.CONNECTING, mqttEndpoint(next), "");
            if (changed) hub.updateConfig(next);
            hub.requestMqttSync();
            refreshStatus();
            return;
        }
        if (changed) hub.updateConfig(next);
        if (!changed) return;
        if (next.assistEnabled && !host.micGranted()) host.requestMicPermission();
        refreshStatus();
    }

    /** 连接阶段变化时刷新按钮下方的文字；这一次保存的最终结果再弹出提示。 */
    private void onMqttLink(KioskHub.MqttLink link) {
        paintMqttLink(link);
        if (!awaitingMqtt || KioskHub.MqttLink.CONNECTING.equals(link.phase)
                || KioskHub.MqttLink.IDLE.equals(link.phase)) {
            return;
        }
        awaitingMqtt = false;
        if (mqttResult == null || mqttResult.getText().length() == 0) return;
        Toast.makeText(getContext(), mqttResult.getText(), Toast.LENGTH_LONG).show();
    }

    /** 把最近一次连接地址和成败写到 MQTT 卡片上。 */
    private void paintMqttLink(KioskHub.MqttLink link) {
        if (mqttResult == null || link == null) return;
        if (KioskHub.MqttLink.CONNECTING.equals(link.phase)) {
            mqttResult.setTextColor(ACCENT);
            mqttResult.setText(ui.getString(R.string.mqtt_connecting, link.endpoint));
        } else if (KioskHub.MqttLink.OK.equals(link.phase)) {
            mqttResult.setTextColor(OK_COLOR);
            mqttResult.setText(ui.getString(R.string.mqtt_connect_ok, link.endpoint));
        } else if (KioskHub.MqttLink.FAIL.equals(link.phase)) {
            mqttResult.setTextColor(FAIL_COLOR);
            String detail = link.detail == null || link.detail.isEmpty()
                    ? ui.getString(R.string.mqtt_connect_unknown) : link.detail;
            mqttResult.setText(ui.getString(R.string.mqtt_connect_fail, link.endpoint, detail));
        } else if (KioskHub.MqttLink.OFF.equals(link.phase)) {
            mqttResult.setTextColor(MUTED);
            mqttResult.setText(ui.getString(R.string.mqtt_connect_off));
        } else {
            mqttResult.setText("");
        }
        mqttResult.setVisibility(mqttResult.getText().length() == 0 ? GONE : VISIBLE);
    }

    /** 界面上显示的主机和端口，不带账号和密码。 */
    private static String mqttEndpoint(KioskConfig config) {
        String host = config.mqttHost.contains(":") ? "[" + config.mqttHost + "]" : config.mqttHost;
        return host + ":" + config.mqttPort;
    }

    /** 是否为 MQTT 卡片上的输入项。 */
    private static boolean isMqttKey(String key) {
        for (String mqttKey : MQTT_KEYS) {
            if (mqttKey.equals(key)) return true;
        }
        return false;
    }

    /** 关掉锁定模式和固定桌面，再交给主界面打开其他应用。 */
    private void releaseKiosk() {
        filling = true;
        if (lockSwitch != null) lockSwitch.setChecked(false);
        filling = false;
        host.releaseKiosk();
    }

    /** 是否已经保存过设置密码。 */
    private boolean hasSavedPin() {
        String pin = hub.config().settingsPin;
        return pin != null && !pin.isEmpty();
    }

    /** 点「保存密码」才校验。未设置时两次都留空表示继续不验证；已设置时当前密码正确且新密码留空表示取消。 */
    private void saveSettingsPin() {
        String saved = hub.config().settingsPin == null ? "" : hub.config().settingsPin;
        String current = draftText(pinCurrent);
        String next = draftText(pinNew);
        String again = draftText(pinConfirm);
        clearError(pinCurrent);
        clearError(pinNew);
        clearError(pinConfirm);
        if (!saved.isEmpty() && !current.equals(saved)) {
            pinCurrent.setError(ui.getString(R.string.pin_wrong));
            return;
        }
        if (next.isEmpty() && again.isEmpty()) {
            storeSettingsPin("");
            return;
        }
        if (!next.equals(again)) {
            pinConfirm.setError(ui.getString(R.string.pin_mismatch));
            return;
        }
        if (!next.matches("\\d{4,8}")) {
            pinNew.setError(ui.getString(R.string.error_pin));
            return;
        }
        storeSettingsPin(next);
    }

    /** 写入设置密码。空字符串表示取消。日志只记动作，不记密码。 */
    private void storeSettingsPin(String pin) {
        KioskConfig current = hub.config();
        String saved = current.settingsPin == null ? "" : current.settingsPin;
        if (!pin.equals(saved)) {
            KioskConfig.Builder builder = current.toBuilder();
            builder.settingsPin = pin;
            hub.updateConfig(builder.build());
            Log.i(KioskApp.LOG, pin.isEmpty() ? "Settings PIN cleared" : "Settings PIN updated");
        }
        resetPinDraft();
    }

    /** 打开面板或保存后清空草稿，已保存的密码不回填到输入框。 */
    private void resetPinDraft() {
        if (pinNew == null) return;
        pinCurrentRow.setVisibility(hasSavedPin() ? VISIBLE : GONE);
        clearDraft(pinCurrent);
        clearDraft(pinNew);
        clearDraft(pinConfirm);
    }

    /** 清空一个密码草稿并去掉错误提示。 */
    private static void clearDraft(EditText input) {
        if (input == null) return;
        input.setText("");
        clearError(input);
    }

    /** 没有错误时不要再 setError(null)，否则输入框会请求把自己滚进屏幕。 */
    private static void clearError(EditText input) {
        if (input != null && input.getError() != null) input.setError(null);
    }

    /** 读取密码草稿，去掉首尾空格。 */
    private static String draftText(EditText input) {
        if (input == null || input.getText() == null) return "";
        return input.getText().toString().trim();
    }

    /** 设置密码专用输入框：失焦不保存，也不放进自动保存字段。 */
    private LinearLayout pinColumn(Context context, String label) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(context, label, 12, MUTED, true);
        l.setPadding(dp(2), dp(8), dp(2), dp(6));
        column.addView(l);
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setTextSize(16);
        input.setTextColor(TEXT);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(FIELD);
        input.setBackground(bg);
        input.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) scroll.noteEditing(input);
            return false;
        });
        input.setOnFocusChangeListener((v, hasFocus) -> {
            GradientDrawable drawable = (GradientDrawable) v.getBackground();
            drawable.setStroke(hasFocus ? dp(2) : 0, ACCENT);
            if (hasFocus) scroll.revealSoon(input);
            else {
                scroll.keepPosition();
                scroll.clearEditing(input);
                scroll.reclaimUnaskedFocus();
            }
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            v.clearFocus();
            return false;
        });
        column.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        column.setTag(input);
        return column;
    }

    /** 开始一个白色分组卡片。标题在左，右侧紧挨一个小问号。 */
    private void section(Context context, String title, int helpBody) {
        card(context);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(context, title, 13, ACCENT, true);
        t.setLetterSpacing(0.04f);
        row.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(helpMark(context, title, helpBody));
        currentCard.addView(row);
    }

    /** 标题旁的小问号。圆圈很小，外层点击区域够手指点。 */
    private View helpMark(Context context, String title, int helpBody) {
        TextView mark = text(context, "?", 12, ACCENT, true);
        mark.setGravity(Gravity.CENTER);
        int visual = dp(18);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(Math.max(1, dp(1)), ACCENT);
        ring.setColor(Color.TRANSPARENT);
        mark.setBackground(ring);
        FrameLayout hit = new FrameLayout(context);
        hit.setMinimumWidth(dp(44));
        hit.setMinimumHeight(dp(36));
        hit.setContentDescription(context.getString(R.string.help_open, title));
        FrameLayout.LayoutParams markLp = new FrameLayout.LayoutParams(visual, visual, Gravity.CENTER_VERTICAL);
        markLp.setMarginStart(dp(4));
        hit.addView(mark, markLp);
        hit.setOnClickListener(v -> showHelp(title, context.getString(helpBody)));
        return hit;
    }

    /** 按当前界面语言显示某一张卡片的说明。窗口属于 Activity，盖住设置抽屉和后面的网页。 */
    private void showHelp(String title, String body) {
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
        Activity activity = findActivity();
        if (activity == null || activity.isFinishing()) {
            Log.w(KioskApp.LOG, "Help dialog skipped because the activity is not available");
            return;
        }
        dismissHelp();

        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        int width = Math.min(dp(460), Math.max(dp(280), screenW - dp(48)));
        int inner = Math.max(dp(120), width - dp(40));
        TextView bodyView = text(activity, body, 15, TEXT, false);
        bodyView.setLineSpacing(dp(3), 1f);
        bodyView.setPadding(0, dp(12), 0, dp(8));
        bodyView.measure(View.MeasureSpec.makeMeasureSpec(inner, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int maxCard = Math.max(dp(240), (int) (screenH * 0.8f));
        int maxBody = Math.max(dp(96), maxCard - dp(130));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        // 内容仍可上下滑动，只是不画出滚动条。
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(bodyView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                Math.min(bodyView.getMeasuredHeight(), maxBody));

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setPadding(dp(20), dp(18), dp(20), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(Color.WHITE);
        card.setBackground(bg);
        card.addView(text(activity, title, 18, TEXT, true));
        card.addView(scroll, scrollLp);
        Button close = button(activity, ui.getString(R.string.close), this::dismissHelp, true);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        closeLp.topMargin = dp(8);
        card.addView(close, closeLp);

        FrameLayout layer = new FrameLayout(activity);
        View dim = new View(activity);
        dim.setBackgroundColor(Color.argb(150, 0, 0, 0));
        dim.setOnClickListener(v -> dismissHelp());
        layer.addView(dim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        layer.addView(card, new LayoutParams(width, LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        Dialog dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setContentView(layer);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attrs);
            WindowCompat.setDecorFitsSystemWindows(window, false);
        }
        dialog.setOnShowListener(d -> hideDialogBars(dialog));
        dialog.setOnDismissListener(d -> {
            if (helpDialog == d) helpDialog = null;
        });
        helpDialog = dialog;
        try {
            dialog.show();
        } catch (RuntimeException ex) {
            helpDialog = null;
            Log.w(KioskApp.LOG, "Failed to show help dialog", ex);
        }
    }

    /** 说明对话框也保持全屏沉浸，避免压过设置面板时把系统栏叫出来。 */
    private void hideDialogBars(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller == null) return;
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    /** 从当前上下文找到承载设置面板的 Activity。 */
    private Activity findActivity() {
        Context context = getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** 关掉说明，不关闭设置面板。 */
    private void dismissHelp() {
        Dialog dialog = helpDialog;
        helpDialog = null;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    /** 创建一张圆角分组卡片并记为当前容器。 */
    private LinearLayout card(Context context) {
        currentCard = new LinearLayout(context);
        currentCard.setOrientation(LinearLayout.VERTICAL);
        currentCard.setPadding(dp(16), dp(16), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(Color.WHITE);
        currentCard.setBackground(bg);
        currentCard.setElevation(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        form.addView(currentCard, lp);
        return currentCard;
    }

    /** 两个短字段并排，离开输入框后保存。 */
    private void pair(Context context, String leftKey, String leftLabel, int leftType,
                      String rightKey, String rightLabel, int rightType) {
        pair(context, leftKey, leftLabel, leftType, rightKey, rightLabel, rightType, true);
    }

    /** 两个短字段并排。commitOnBlur 为假时只改显示，等专门的保存按钮写入。 */
    private void pair(Context context, String leftKey, String leftLabel, int leftType,
                      String rightKey, String rightLabel, int rightType, boolean commitOnBlur) {
        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        left.setMarginEnd(dp(10));
        line.addView(fieldColumn(context, leftKey, leftLabel, leftType, commitOnBlur), left);
        line.addView(fieldColumn(context, rightKey, rightLabel, rightType, commitOnBlur),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        currentCard.addView(line, fullWidth());
    }

    /** 添加整行输入框，离开输入框后保存。 */
    private void field(Context context, String key, String label, int inputType) {
        field(context, key, label, inputType, true);
    }

    /** 添加整行输入框。commitOnBlur 为假时不在失焦时写入配置。 */
    private void field(Context context, String key, String label, int inputType, boolean commitOnBlur) {
        currentCard.addView(fieldColumn(context, key, label, inputType, commitOnBlur), fullWidth());
    }

    /** 创建标题加输入框，离开输入框后保存。 */
    private LinearLayout fieldColumn(Context context, String key, String label, int inputType) {
        return fieldColumn(context, key, label, inputType, true);
    }

    /** 创建标题加输入框，不直接放入卡片，方便并排。 */
    private LinearLayout fieldColumn(Context context, String key, String label, int inputType, boolean commitOnBlur) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(context, label, 12, MUTED, true);
        l.setPadding(dp(2), dp(8), dp(2), dp(6));
        column.addView(l);
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setTextSize(16);
        input.setTextColor(TEXT);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(FIELD);
        input.setBackground(bg);
        input.setOnTouchListener((v, event) -> {
            // 只有手指点到的输入框才允许为键盘让位，焦点自己跳上去不算
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) scroll.noteEditing(input);
            return false;
        });
        input.setOnFocusChangeListener((v, hasFocus) -> {
            GradientDrawable drawable = (GradientDrawable) v.getBackground();
            drawable.setStroke(hasFocus ? dp(2) : 0, ACCENT);
            if (hasFocus) {
                scroll.revealSoon(input);
            } else {
                // 失焦保存会触发布局；先钉住，再处理焦点跳到顶部输入框的情况
                scroll.keepPosition();
                scroll.clearEditing(input);
                if (commitOnBlur) commit();
                scroll.reclaimUnaskedFocus();
            }
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            v.clearFocus();
            return false;
        });
        column.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        fields.put(key, input);
        return column;
    }

    /** 0 到 23 点的显示列。表单只放当前小时，样式与旁边的输入框一致；点一下才弹出选择。 */
    private LinearLayout hourColumn(Context context, String key, String label) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(context, label, 12, MUTED, true);
        l.setPadding(dp(2), dp(8), dp(2), dp(6));
        column.addView(l);
        TextView value = text(context, "0", 16, TEXT, false);
        value.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(FIELD);
        value.setBackground(bg);
        value.setClickable(true);
        value.setFocusable(true);
        value.setMinHeight(dp(48));
        value.setGravity(Gravity.CENTER_VERTICAL);
        HourField field = new HourField(value);
        value.setOnClickListener(v -> openHourDialog(label, field));
        column.addView(value, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        hours.put(key, field);
        return column;
    }

    /** 弹出 0 到 23 的滚轮。确认后写回并走现有保存；取消、点外侧或返回都不改表单上的小时。 */
    private void openHourDialog(String label, HourField field) {
        if (hourDialog != null && hourDialog.isShowing()) hourDialog.dismiss();
        // 对话框必须挂在 Activity 上。按钮文案用设置面板当前语言。
        Activity activity = findActivity();
        if (activity == null) {
            Log.w(KioskApp.LOG, "Hour dialog skipped because the activity is not available");
            return;
        }
        HourPicker picker = new HourPicker(activity);
        picker.setHour(field.getHour());
        LinearLayout wrap = new LinearLayout(activity);
        wrap.setPadding(dp(12), dp(8), dp(12), 0);
        wrap.addView(picker, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180)));
        hourDialog = new AlertDialog.Builder(activity)
                .setTitle(label)
                .setView(wrap)
                .setPositiveButton(ui.getString(R.string.pin_confirm), (dialog, which) -> {
                    field.setHour(picker.getHour());
                    commit();
                })
                .setNegativeButton(ui.getString(R.string.cancel), (dialog, which) -> dialog.cancel())
                .create();
        hourDialog.setOnShowListener(dialog -> {
            if (hourDialog.getWindow() != null) {
                hourDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            }
        });
        hourDialog.show();
    }

    /** 把配置里的小时收成 0 到 23。选择器本身不会给出这个范围以外的值。 */
    private static int hourValue(String raw) {
        try {
            int parsed = Integer.parseInt(raw == null ? "" : raw.trim());
            if (parsed < 0) return 0;
            if (parsed > 23) return 23;
            return parsed;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 整行百分比拖动条。min、max 必须与 ConfigForm 的范围一致。 */
    private void percent(Context context, String key, String label, int min, int max) {
        currentCard.addView(percentColumn(context, key, label, min, max), fullWidth());
    }

    /** 左右等宽并排，百分比和输入框都可以放。 */
    private void placePair(Context context, View leftView, View rightView) {
        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        left.setMarginEnd(dp(10));
        line.addView(leftView, left);
        line.addView(rightView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        currentCard.addView(line, fullWidth());
    }

    /**
     * 一条百分比拖动条：标签保留原文，旁边实时显示整数和 %。
     * 拖动只改显示，松手才保存。
     */
    private LinearLayout percentColumn(Context context, String key, String label, int min, int max) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(2), dp(8), dp(2), 0);
        TextView name = text(context, label, 12, MUTED, true);
        name.setLineSpacing(dp(2), 1f);
        head.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = text(context, min + "%", 14, ACCENT, true);
        value.setGravity(Gravity.END);
        value.setMinWidth(dp(52));
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        valueLp.setMarginStart(dp(8));
        head.addView(value, valueLp);
        column.addView(head);

        SeekBar bar = new SeekBar(context);
        bar.setMin(min);
        bar.setMax(max);
        bar.setProgress(min);
        // 拖动条不拿焦点，否则松手后 ScrollView 会把它或别的控件滚进屏幕
        bar.setFocusable(false);
        bar.setFocusableInTouchMode(false);
        bar.setSplitTrack(false);
        bar.setContentDescription(label);
        int thumbSize = dp(28);
        bar.setPadding(thumbSize / 2, dp(8), thumbSize / 2, dp(8));
        bar.setThumbOffset(thumbSize / 2);
        bar.setProgressDrawable(percentTrack());
        bar.setThumb(percentThumb(thumbSize));
        PercentBar item = new PercentBar(min, max, bar, value, "%");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // 拖动过程只更新数字，不写配置
                item.value.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // 开始拖动时不保存
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 松手后走现有保存。先钉住滚动，并放下输入框焦点，避免列表跳走。
                if (filling) return;
                scroll.keepPosition();
                scroll.releaseEditFocus();
                commit();
            }
        });
        column.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        percents.put(key, item);
        return column;
    }

    /** 圆角轨道：底色沿用分隔线，已滑过的部分用现有强调色。 */
    private LayerDrawable percentTrack() {
        int height = dp(8);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(height);
        background.setColor(LINE);
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(height);
        fill.setColor(ACCENT);
        ClipDrawable clip = new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new Drawable[] {background, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        layers.setLayerHeight(0, height);
        layers.setLayerHeight(1, height);
        layers.setLayerGravity(0, Gravity.CENTER_VERTICAL);
        layers.setLayerGravity(1, Gravity.CENTER_VERTICAL);
        return layers;
    }

    /** 圆形拖动手柄，尺寸适合手指。 */
    private GradientDrawable percentThumb(int size) {
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(ACCENT);
        thumb.setSize(size, size);
        return thumb;
    }

    /** 配置文字转整数；无法解析时用拖动条下限。填充阶段不保存。 */
    private static int percentOrMin(String raw, PercentBar item) {
        if (raw == null) return item.min;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return item.min;
        }
    }

    /** 按下点是否落在百分比拖动条上，避免向左滑关闭抢走拖动。 */
    private boolean touchHitsPercent(MotionEvent ev) {
        int extra = dp(12);
        int[] loc = new int[2];
        float x = ev.getRawX();
        float y = ev.getRawY();
        for (PercentBar item : percents.values()) {
            item.bar.getLocationOnScreen(loc);
            if (x >= loc[0] - extra && x <= loc[0] + item.bar.getWidth() + extra
                    && y >= loc[1] - extra && y <= loc[1] + item.bar.getHeight() + extra) {
                return true;
            }
        }
        return false;
    }

    /** 添加开关行：说明在左，开关在右，拨动后立即保存。 */
    private Switch toggle(Context context, String label) {
        return toggle(context, label, true);
    }

    /** 添加开关行。commitOnChange 为假时只改开关，等专门的保存按钮写入。 */
    private Switch toggle(Context context, String label, boolean commitOnChange) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));
        row.setPadding(dp(2), dp(8), 0, dp(4));
        TextView l = text(context, label, 15, TEXT, true);
        l.setLineSpacing(dp(2), 1f);
        row.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Switch s = new Switch(context);
        s.setShowText(false);
        // 开关不拿焦点。填充表单时会 setChecked，这时不能清焦点也不能保存。
        s.setFocusable(false);
        s.setFocusableInTouchMode(false);
        if (commitOnChange) s.setOnCheckedChangeListener((button, checked) -> {
            if (filling) return;
            scroll.keepPosition();
            scroll.releaseEditFocus();
            commit();
        });
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        switchLp.setMarginStart(dp(12));
        row.addView(s, switchLp);
        currentCard.addView(row, fullWidth());
        return s;
    }

    /**
     * 用已保存的语言重建设置文案。面板保持打开。
     * 语言与当前显示相同时直接返回。
     */
    /** 导入完成后按新配置和新语言重建设置，不保留导入前未保存的草稿。 */
    void reloadImported(String language) {
        if (hourDialog != null) hourDialog.dismiss();
        dismissHelp();
        shownLanguage = language == null || language.isEmpty() ? AppLocales.effective(panelBase) : language;
        ui = AppLocales.forLanguage(panelBase, shownLanguage);
        fields.clear();
        percents.clear();
        hours.clear();
        form.removeAllViews();
        buildForm(ui);
        populate(hub.config());
        applySetupChrome();
    }

    /** 扫描局域网 8123，点一项就写入首页地址。 */
    private void findHa() {
        Context context = ui == null ? getContext() : ui;
        Dialog dialog = new Dialog(getContext());
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, pad);
        TextView hint = text(context, context.getString(R.string.find_ha_hint), 14, MUTED, false);
        hint.setLineSpacing(dp(3), 1f);
        box.addView(hint, fullWidth());
        TextView status = text(context, context.getString(R.string.find_ha_loading), 15, TEXT, false);
        box.addView(status, fullWidth());
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scrollList = new ScrollView(context);
        scrollList.addView(list);
        box.addView(scrollList, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240)));
        dialog.setContentView(box);
        dialog.show();
        String local = NetUtil.localIpv4(context);
        new Thread(() -> {
            List<String> found = HaDiscovery.scan(local);
            post(() -> {
                if (!dialog.isShowing()) return;
                list.removeAllViews();
                if (found.isEmpty()) {
                    status.setText(HaDiscovery.subnetPrefix(local) == null
                            ? R.string.find_ha_no_lan : R.string.find_ha_empty);
                    return;
                }
                status.setText(R.string.find_ha_done);
                for (String url : found) {
                    list.addView(button(context, url, () -> {
                        EditText home = fields.get(ConfigForm.HOME_URL);
                        if (home != null) home.setText(url);
                        commit();
                        dialog.dismiss();
                    }, false), fullWidth());
                }
            });
        }, "ha-discovery").start();
    }

    /** 打开仪表盘选择。令牌只保存在本机。 */
    private void chooseDashboard() {
        Context context = ui == null ? getContext() : ui;
        Dialog dialog = new Dialog(getContext());
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, pad);
        TextView hint = text(context, context.getString(R.string.ha_token_hint), 14, MUTED, false);
        hint.setLineSpacing(dp(3), 1f);
        box.addView(hint, fullWidth());
        EditText token = new EditText(context);
        token.setText(hub.store().haToken());
        token.setHint(context.getString(R.string.ha_token));
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        token.setSingleLine(true);
        box.addView(token, fullWidth());
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView choices = new ScrollView(context);
        choices.addView(list);
        LinearLayout.LayoutParams choicesLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(320));
        choicesLp.topMargin = dp(12);
        box.addView(choices, choicesLp);
        box.addView(button(context, context.getString(R.string.dashboard_load), () -> loadDashboards(token, list, dialog), true),
                fullWidth());
        box.addView(button(context, context.getString(R.string.close), dialog::dismiss, false), fullWidth());
        dialog.setContentView(box);
        dialog.show();
    }

    /** 在后台读取仪表盘，结果回到主线程。 */
    private void loadDashboards(EditText tokenInput, LinearLayout list, Dialog dialog) {
        String token = tokenInput.getText() == null ? "" : tokenInput.getText().toString().trim();
        hub.store().saveHaToken(token);
        EditText home = fields.get(ConfigForm.HOME_URL);
        String homeUrl = home == null || home.getText() == null ? hub.config().homeUrl : home.getText().toString();
        Context context = ui == null ? getContext() : ui;
        list.removeAllViews();
        list.addView(text(context, context.getString(R.string.dashboard_loading), 14, MUTED, false));
        new Thread(() -> {
            HaDashboards.Outcome outcome = HaDashboards.load(homeUrl, token);
            post(() -> showDashboards(outcome, list, dialog));
        }, "ha-dashboards").start();
    }

    /** 把仪表盘列成按钮。点一项就写入首页并保存。 */
    private void showDashboards(HaDashboards.Outcome outcome, LinearLayout list, Dialog dialog) {
        Context context = ui == null ? getContext() : ui;
        list.removeAllViews();
        if (outcome.error != null) {
            int message = switch (outcome.error) {
                case "unauthorized" -> R.string.dashboard_unauthorized;
                case "forbidden" -> R.string.dashboard_forbidden;
                case "invalid_url" -> R.string.dashboard_invalid_url;
                case "bad_response" -> R.string.dashboard_bad_response;
                default -> R.string.dashboard_unreachable;
            };
            list.addView(text(context, context.getString(message), 14, TEXT, false));
            return;
        }
        if (outcome.items.isEmpty()) {
            list.addView(text(context, context.getString(R.string.dashboard_empty), 14, TEXT, false));
            return;
        }
        for (HaDashboards.Item item : outcome.items) {
            list.addView(button(context, item.title, () -> {
                EditText home = fields.get(ConfigForm.HOME_URL);
                if (home != null) home.setText(item.url);
                commit();
                dialog.dismiss();
            }, false), fullWidth());
        }
    }

    void applyLocale(String language) {
        if (language == null || language.equals(shownLanguage)) return;
        // 整表重绘会把内容高度瞬间压扁。先钉住，布局完成后再写回，不要在这里 scrollTo。
        scroll.keepPosition();
        Map<String, String> text = new HashMap<>();
        Map<String, CharSequence> errors = new HashMap<>();
        for (Map.Entry<String, EditText> e : fields.entrySet()) {
            text.put(e.getKey(), e.getValue().getText().toString());
            CharSequence error = e.getValue().getError();
            if (error != null) errors.put(e.getKey(), error);
        }
        Map<String, Integer> percentValues = new HashMap<>();
        for (Map.Entry<String, PercentBar> e : percents.entrySet()) {
            percentValues.put(e.getKey(), e.getValue().bar.getProgress());
        }
        Map<String, Integer> hourValues = new HashMap<>();
        for (Map.Entry<String, HourField> e : hours.entrySet()) {
            hourValues.put(e.getKey(), e.getValue().getHour());
        }
        boolean motion = motionSwitch != null && motionSwitch.isChecked();
        boolean schedule = scheduleSwitch != null && scheduleSwitch.isChecked();
        boolean daily = dailySwitch != null && dailySwitch.isChecked();
        boolean sleep = sleepSwitch != null && sleepSwitch.isChecked();
        boolean screensaver = screensaverSwitch != null && screensaverSwitch.isChecked();
        boolean screensaverWeather = screensaverWeatherSwitch != null && screensaverWeatherSwitch.isChecked();
        boolean screensaverLunar = screensaverLunarSwitch != null && screensaverLunarSwitch.isChecked();
        boolean mqtt = mqttSwitch != null && mqttSwitch.isChecked();
        boolean lan = lanSwitch != null && lanSwitch.isChecked();
        boolean lock = lockSwitch != null && lockSwitch.isChecked();
        String pinCurrentDraft = pinCurrent == null ? "" : pinCurrent.getText().toString();
        String pinNewDraft = pinNew == null ? "" : pinNew.getText().toString();
        String pinConfirmDraft = pinConfirm == null ? "" : pinConfirm.getText().toString();
        // 未确认的小时滚轮和旧语言说明关掉，设置抽屉本身不关
        if (hourDialog != null) hourDialog.dismiss();
        dismissHelp();
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);

        shownLanguage = language;
        ui = AppLocales.forLanguage(panelBase, language);
        fields.clear();
        percents.clear();
        hours.clear();
        form.removeAllViews();
        buildForm(ui);

        filling = true;
        for (Map.Entry<String, String> e : text.entrySet()) {
            EditText input = fields.get(e.getKey());
            if (input == null) continue;
            input.setText(e.getValue());
            CharSequence error = errors.get(e.getKey());
            if (error != null) input.setError(error);
        }
        for (Map.Entry<String, Integer> e : percentValues.entrySet()) {
            PercentBar bar = percents.get(e.getKey());
            if (bar != null) bar.setValue(e.getValue());
        }
        for (Map.Entry<String, Integer> e : hourValues.entrySet()) {
            HourField hour = hours.get(e.getKey());
            if (hour != null) hour.setHour(e.getValue());
        }
        if (motionSwitch != null) motionSwitch.setChecked(motion);
        if (scheduleSwitch != null) scheduleSwitch.setChecked(schedule);
        if (dailySwitch != null) dailySwitch.setChecked(daily);
        if (sleepSwitch != null) sleepSwitch.setChecked(sleep);
        if (screensaverSwitch != null) screensaverSwitch.setChecked(screensaver);
        if (screensaverWeatherSwitch != null) screensaverWeatherSwitch.setChecked(screensaverWeather);
        if (screensaverLunarSwitch != null) screensaverLunarSwitch.setChecked(screensaverLunar);
        if (mqttSwitch != null) mqttSwitch.setChecked(mqtt);
        if (lanSwitch != null) lanSwitch.setChecked(lan);
        if (lockSwitch != null) lockSwitch.setChecked(lock);
        if (pinCurrent != null) pinCurrent.setText(pinCurrentDraft);
        if (pinNew != null) pinNew.setText(pinNewDraft);
        if (pinConfirm != null) pinConfirm.setText(pinConfirmDraft);
        if (pinCurrentRow != null) pinCurrentRow.setVisibility(hasSavedPin() ? VISIBLE : GONE);
        filling = false;
        refreshStatus();
        applySetupChrome();
    }

    /** 标题旁只有一个按钮：文字是当前语言，点一下切到另一种并只重建设置文案。 */
    private Button languageButton(Context context) {
        String current = shownLanguage == null ? AppLocales.effective(context) : shownLanguage;
        Button chip = button(context, AppLocales.buttonLabel(current), () -> {
            String now = shownLanguage == null ? AppLocales.effective(getContext()) : shownLanguage;
            host.onLanguageChanged(AppLocales.toggle(now));
        }, true);
        chip.setTextSize(13);
        chip.setMinHeight(dp(36));
        chip.setMinimumHeight(dp(36));
        chip.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        chip.setLayoutParams(lp);
        return chip;
    }

    /** 创建圆角按钮。filled 为实心主操作，其余为描边次操作。 */
    private Button button(Context context, String label, Runnable action, boolean filled) {
        Button b = new Button(context);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setMinHeight(dp(46));
        b.setMinimumHeight(dp(46));
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setGravity(Gravity.CENTER);
        b.setStateListAnimator(null);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        if (filled) {
            bg.setColor(ACCENT);
            b.setTextColor(Color.WHITE);
            b.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            bg.setColor(Color.WHITE);
            bg.setStroke(dp(1), LINE);
            b.setTextColor(ACCENT);
        }
        b.setBackground(bg);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    /** 往状态区追加一格，两格一行。 */
    private void addStat(String label, String value) {
        if (statCount % 2 == 0) {
            statRow = new LinearLayout(getContext());
            statRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (statCount > 0) rowLp.topMargin = dp(8);
            statusGrid.addView(statRow, rowLp);
        }
        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(FIELD);
        cell.setBackground(bg);
        TextView name = text(getContext(), label, 11, MUTED, false);
        TextView number = text(getContext(), value, 16, TEXT, true);
        number.setPadding(0, dp(2), 0, 0);
        cell.addView(name);
        cell.addView(number);
        statValues.add(number);
        LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (statCount % 2 == 0) cellLp.setMarginEnd(dp(8));
        statRow.addView(cell, cellLp);
        statCount++;
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

    /** 把校验代码翻译成当前语言的提示。 */
    private String errorText(String key, String code) {
        Context context = ui;
        if (code.startsWith(ConfigForm.ERR_RANGE_PREFIX)) {
            String[] parts = code.split(":");
            int min = Integer.parseInt(parts[1]);
            int max = Integer.parseInt(parts[2]);
            return context.getString(R.string.error_range, context.getString(rangeLabel(key)), min, max);
        }
        switch (code) {
            case ConfigForm.ERR_URL:
                return context.getString(R.string.error_url);
            case ConfigForm.ERR_MQTT_HOST:
                return context.getString(R.string.error_mqtt_host);
            case ConfigForm.ERR_TOPIC:
                return context.getString(R.string.error_topic);
            case ConfigForm.ERR_PREFIX:
                return context.getString(R.string.error_prefix);
            case ConfigForm.ERR_LAN_PASSWORD:
                return context.getString(R.string.error_lan_password, ConfigForm.MIN_LAN_PASSWORD_LENGTH);
            case ConfigForm.ERR_PIN:
                return context.getString(R.string.error_pin);
            default:
                return code;
        }
    }

    /** 范围错误使用的字段短名。 */
    private static int rangeLabel(String key) {
        switch (key) {
            case ConfigForm.IDLE_RELOAD_HOURS:
                return R.string.label_idle_reload;
            case ConfigForm.RETRY_SECONDS:
                return R.string.label_retry;
            case ConfigForm.WATCH_BRIGHTNESS:
                return R.string.label_watch;
            case ConfigForm.STANDBY_BRIGHTNESS:
                return R.string.label_standby;
            case ConfigForm.DIM_DELAY_SECONDS:
                return R.string.label_dim;
            case ConfigForm.DAY_START_HOUR:
                return R.string.label_day_start;
            case ConfigForm.NIGHT_START_HOUR:
                return R.string.label_night_start;
            case ConfigForm.DAY_BRIGHTNESS:
                return R.string.label_day;
            case ConfigForm.NIGHT_BRIGHTNESS:
                return R.string.label_night;
            case ConfigForm.DAILY_RELOAD_HOUR:
                return R.string.label_daily_hour;
            case ConfigForm.DAILY_RELOAD_MINUTE:
                return R.string.label_daily_minute;
            case ConfigForm.SLEEP_START_HOUR:
                return R.string.label_sleep_start;
            case ConfigForm.SLEEP_END_HOUR:
                return R.string.label_sleep_end;
            case ConfigForm.VOLUME:
                return R.string.label_volume;
            case ConfigForm.WATCHDOG_SECONDS:
                return R.string.label_watchdog;
            case ConfigForm.SPEECH_WAIT_SECONDS:
                return R.string.speech_wait_seconds;
            case ConfigForm.MOTION_SENSITIVITY:
                return R.string.label_sensitivity;
            case ConfigForm.PRESENCE_HOLD_SECONDS:
                return R.string.label_hold;
            case ConfigForm.MQTT_PORT:
                return R.string.label_mqtt_port;
            case ConfigForm.MQTT_STATE_INTERVAL:
                return R.string.label_interval;
            case ConfigForm.LAN_PORT:
                return R.string.label_lan_port;
            default:
                return R.string.app_name;
        }
    }

    /** 布尔值转当前语言。 */
    private String yes(boolean value) {
        return ui.getString(value ? R.string.yes : R.string.no);
    }

    /** 百分比；尚未采样时显示未知。 */
    private String percentText(int value) {
        return value < 0 ? ui.getString(R.string.unknown) : value + "%";
    }

    /** 内存占用，能读到总量时附上 MB。 */
    private String memoryText(KioskStatus status) {
        if (status.memoryPercent < 0) return ui.getString(R.string.unknown);
        if (status.memoryUsedMb < 0 || status.memoryTotalMb < 0) return status.memoryPercent + "%";
        return status.memoryPercent + "%  " + status.memoryUsedMb + "/" + status.memoryTotalMb + " MB";
    }

    /** 应用占用 MB。 */
    private String megabyteText(int value) {
        return value < 0 ? ui.getString(R.string.unknown) : value + " MB";
    }

    /** 电池温度，单位是 0.1 摄氏度。 */
    private String temperatureText(int tenths) {
        if (tenths < -200) return ui.getString(R.string.unknown);
        return String.format(java.util.Locale.ROOT, "%.1f°C", tenths / 10.0);
    }

    /** 充电状态转当前语言。 */
    private String chargeLabel(String state) {
        switch (state) {
            case BatteryState.CHARGING:
                return ui.getString(R.string.charge_charging);
            case BatteryState.DISCHARGING:
                return ui.getString(R.string.charge_discharging);
            case BatteryState.NOT_CHARGING:
                return ui.getString(R.string.charge_not_charging);
            case BatteryState.FULL:
                return ui.getString(R.string.charge_full);
            default:
                return state.toLowerCase(Locale.ROOT);
        }
    }

    /** 表单上的小时：只记住并显示当前值，不放滚轮。 */
    private static final class HourField {
        final TextView value;
        int hour;

        /** 绑定显示用的文字。初始小时由打开面板时的配置填入。 */
        HourField(TextView value) {
            this.value = value;
        }

        /** 当前选中的小时。保存其他项时也读这个值。 */
        int getHour() {
            return hour;
        }

        /** 写入 0 到 23，并刷新文字。不在这里保存。 */
        void setHour(int next) {
            hour = next < 0 ? 0 : Math.min(next, 23);
            value.setText(Integer.toString(hour));
        }
    }

    /**
     * 对话框里的 0 到 23 小时滚轮。
     * 中间数字不能获得焦点，也不能弹出键盘，只能加减或滑动。
     */
    private final class HourPicker extends NumberPicker {
        /** 建立 0 到 23 的滚轮，并锁住内部输入框。 */
        HourPicker(Context context) {
            super(new android.view.ContextThemeWrapper(context, R.style.ThemeOverlay_TabletKiosk_HourPicker));
            setMinValue(0);
            setMaxValue(23);
            setWrapSelectorWheel(true);
            setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
            setLongClickable(false);
            lockInput();
            tintWheel();
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(FIELD);
            setBackground(bg);
        }

        /** 当前选中的小时。 */
        int getHour() {
            return getValue();
        }

        /** 对话框打开时停在当前小时。setValue 不会写回表单。 */
        void setHour(int hour) {
            setValue(hour < 0 ? 0 : Math.min(hour, 23));
        }

        /** 点击不打开键盘。 */
        @Override
        public boolean performClick() {
            return true;
        }

        /** 长按不打开键盘。 */
        @Override
        public boolean performLongClick() {
            return true;
        }

        /** 按下时就接管手势，避免中间文字获得焦点，也避免外层滚动抢走滑动。 */
        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            boolean intercepted = super.onInterceptTouchEvent(event);
            return event.getActionMasked() == MotionEvent.ACTION_DOWN || intercepted;
        }

        /** 内部输入框一加入就改成强调色，并取消可编辑。滚轮数字会抄这个颜色。 */
        @Override
        public void addView(View child, int index, android.view.ViewGroup.LayoutParams params) {
            prepareInput(child);
            super.addView(child, index, params);
        }

        /** 构造结束后再锁一次，防止父类把输入框重新设成可编辑。 */
        private void lockInput() {
            for (int i = 0; i < getChildCount(); i++) {
                prepareInput(getChildAt(i));
            }
        }

        /** 中间文字只显示，不能聚焦、不能输入。 */
        private void prepareInput(View child) {
            if (!(child instanceof EditText)) return;
            EditText input = (EditText) child;
            input.setTextColor(ACCENT);
            input.setTextSize(22);
            input.setTypeface(Typeface.DEFAULT_BOLD);
            input.setFocusable(false);
            input.setFocusableInTouchMode(false);
            input.setClickable(false);
            input.setLongClickable(false);
            input.setCursorVisible(false);
            input.setInputType(InputType.TYPE_NULL);
            input.setKeyListener(null);
            input.setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO);
        }

        /** 使用 Android 10 起提供的公开接口设置滚轮数字颜色，分隔线由局部主题着色。 */
        private void tintWheel() {
            setTextColor(ACCENT);
        }
    }

    /** 一条百分比拖动条及其实时数字。 */
    private static final class PercentBar {
        final int min;
        final int max;
        final SeekBar bar;
        final TextView value;
        final String suffix;

        /** 记下范围和控件，便于填充与保存。suffix 为空时只显示整数。 */
        PercentBar(int min, int max, SeekBar bar, TextView value, String suffix) {
            this.min = min;
            this.max = max;
            this.bar = bar;
            this.value = value;
            this.suffix = suffix == null ? "" : suffix;
        }

        /** 当前整数文本，交给 ConfigForm 解析。 */
        String text() {
            return Integer.toString(bar.getProgress());
        }

        /** 按配置定位。调用方在 filling 为真时使用，避免触发保存。 */
        void setValue(int next) {
            int clamped = Math.max(min, Math.min(max, next));
            bar.setProgress(clamped);
            value.setText(clamped + suffix);
        }
    }

    /**
     * 设置列表。手指拖动和惯性滑动才改变锚点。
     * 焦点、子矩形、平滑滚动和布局夹紧都写回锚点。
     * 只有手指正在编辑、且被键盘挡住的那个输入框才允许自动滚进可见区，滚完后锚点改到新位置。
     */
    private final class StableScroll extends ScrollView {
        /** 保存、刷新或布局之后应回到的 scrollY。 */
        private int anchorY;
        /** 手指正在拖动列表。 */
        private boolean userDrag;
        /** 手指甩动后的惯性滚动。 */
        private boolean flinging;
        /** 连续两帧位置不变则认为惯性结束。 */
        private int flingStable = Integer.MIN_VALUE;
        /** 正在把被键盘挡住的输入框滚进可见区。 */
        private boolean revealEdit;
        /** 这次滚动是校验失败后主动把那个输入框露出来。 */
        private boolean allowOnce;
        /** 手指点中、允许为键盘让位的输入框。焦点自己跳上去的不算。 */
        private EditText editing;
        /** 当前这次键盘弹出已经为 editing 让过位。 */
        private boolean revealedForIme;
        /** 上次见到的键盘底部 inset，变化时才允许再让一次位。 */
        private int lastIme;
        /** 布局还没把内容高度量回来时，暂时不要把跳走的位置记成锚点。 */
        private boolean holdAnchor;
        /** 写回锚点的剩余尝试次数。大于 0 表示已经排过一次。 */
        private int restoreAttempts;
        /** 已经排了收回意外焦点的任务，避免失焦回调互相触发。 */
        private boolean reclaimPosted;
        /** 正在主动清掉输入框焦点。 */
        private boolean releasingFocus;
        /** 限制自动滚动日志，避免每一帧都打。 */
        private long lastAutoScrollLogMs;

        /** 使用普通滚动视图的测量和手势，并监听键盘 inset。 */
        StableScroll(Context context) {
            super(context);
            ViewCompat.setOnApplyWindowInsetsListener(this, (v, insets) -> {
                onImeInset(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
                return insets;
            });
        }

        /** 手指按下某个输入框时记下它。只有这个框被键盘挡住才自动滚动。 */
        void noteEditing(EditText field) {
            if (field == null || editing == field) return;
            editing = field;
            revealedForIme = false;
        }

        /** 这个输入框失焦后不再为它让位。 */
        void clearEditing(EditText field) {
            if (editing != field) return;
            editing = null;
            revealedForIme = false;
        }

        /** 获得焦点后再看一次键盘。没点过的输入框直接忽略。 */
        void revealSoon(EditText field) {
            if (field != null && field == editing) post(this::revealEditingIfCovered);
        }

        /** 校验失败时允许这一次把指定输入框滚进屏幕，并把新位置当成锚点。 */
        void focusField(View field) {
            if (field == null) return;
            allowOnce = true;
            try {
                field.requestFocus();
                anchorY = getScrollY();
            } finally {
                allowOnce = false;
            }
        }

        /**
         * 保存或刷新前记下当前位置。
         * 若 scrollY 已经被改走，保留原锚点，等布局结束后写回。
         */
        void keepPosition() {
            if (moveAllowed()) return;
            if (!holdAnchor) {
                int current = getScrollY();
                // 锚点还是 0 但用户已经滑走时，补记当前可见位置。
                if (current == anchorY || anchorY == 0) anchorY = current;
                holdAnchor = true;
            }
            if (restoreAttempts > 0) return;
            restoreAttempts = 4;
            post(this::restoreHeldPosition);
        }

        /** 开关、拖动条松手时放下输入框焦点并收起键盘，避免焦点跳走后把列表拉开。 */
        void releaseEditFocus() {
            if (releasingFocus) return;
            View focused = findFocus();
            if (!(focused instanceof EditText)) return;
            releasingFocus = true;
            try {
                keepPosition();
                clearEditing((EditText) focused);
                hideKeyboard();
                focused.clearFocus();
                requestFocusFromTouch();
            } finally {
                releasingFocus = false;
            }
        }

        /** 失焦后如果焦点落到用户没点的输入框上，把焦点还给滚动容器。 */
        void reclaimUnaskedFocus() {
            if (reclaimPosted || releasingFocus) return;
            reclaimPosted = true;
            post(this::reclaimNow);
        }

        /** 键盘高度变化时才允许再为输入框让一次位。 */
        private void onImeInset(int ime) {
            if (ime <= 0) {
                revealedForIme = false;
                lastIme = 0;
                return;
            }
            if (ime != lastIme) {
                revealedForIme = false;
                lastIme = ime;
            }
            post(this::revealEditingIfCovered);
        }

        /** 手指拖动、惯性、键盘让位或主动露出错误框时，允许改变 scrollY。 */
        private boolean moveAllowed() {
            return userDrag || flinging || revealEdit || allowOnce;
        }

        /** 子视图还没量出高度时返回 -1，避免把锚点收成 0。 */
        private int maxScrollY() {
            if (getChildCount() == 0) return -1;
            View child = getChildAt(0);
            int childHeight = child.getHeight();
            int viewport = getHeight() - getPaddingTop() - getPaddingBottom();
            if (childHeight <= 0 || viewport <= 0) return -1;
            return Math.max(0, childHeight - viewport);
        }

        /** 布局完成后若位置被改走，写回锚点。内容暂时比锚点短就再等一帧。 */
        private void restoreHeldPosition() {
            if (moveAllowed()) {
                holdAnchor = false;
                restoreAttempts = 0;
                return;
            }
            int max = maxScrollY();
            if (max < 0 || anchorY > max) {
                if (--restoreAttempts > 0) {
                    post(this::restoreHeldPosition);
                    return;
                }
                restoreAttempts = 0;
                holdAnchor = false;
                if (max >= 0 && anchorY > max) anchorY = max;
                else return;
            } else {
                restoreAttempts = 0;
            }
            int target = Math.min(anchorY, max);
            int before = getScrollY();
            if (before != target) {
                super.scrollTo(getScrollX(), target);
                if (Math.abs(before - getScrollY()) >= dp(8)) {
                    Log.i(KioskApp.LOG, "Restored settings scrollY from " + before + " to " + getScrollY());
                }
            }
            holdAnchor = false;
        }

        /** 执行收回焦点。请求焦点期间不再排队下一次，避免循环。 */
        private void reclaimNow() {
            try {
                View focused = findFocus();
                if (!(focused instanceof EditText) || focused == editing) return;
                keepPosition();
                hideKeyboard();
                requestFocusFromTouch();
                Log.i(KioskApp.LOG, "Cleared unasked settings field focus");
            } finally {
                reclaimPosted = false;
            }
        }

        /** 收起软键盘。不记录输入内容。 */
        private void hideKeyboard() {
            InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }

        /**
         * 正在编辑的输入框底部被键盘挡住时，只滚动刚好露出它的距离。
         * 滚完更新锚点，后面的刷新沿用这个位置。
         */
        private boolean revealEditingIfCovered() {
            EditText field = editing;
            if (field == null || revealedForIme || userDrag || flinging || !field.isFocused() || !field.isShown()) {
                return false;
            }
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(this);
            int ime = insets == null ? 0 : insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            // 没有键盘 inset 就不滚动。可见区域变矮也可能只是系统栏，不能当成键盘。
            if (ime <= 0) return false;
            Rect frame = new Rect();
            getWindowVisibleDisplayFrame(frame);
            int limit = frame.bottom;
            View root = getRootView();
            if (root != null) limit = Math.min(limit, root.getHeight() - ime);
            int[] loc = new int[2];
            field.getLocationOnScreen(loc);
            int margin = dp(12);
            int top = loc[1];
            int bottom = top + field.getHeight();
            int dy = 0;
            if (bottom + margin > limit) dy = bottom + margin - limit;
            else if (top < frame.top + margin) dy = top - (frame.top + margin);
            revealedForIme = true;
            if (dy == 0) return false;
            revealEdit = true;
            try {
                super.scrollTo(getScrollX(), Math.max(0, getScrollY() + dy));
                anchorY = getScrollY();
            } finally {
                revealEdit = false;
            }
            Log.i(KioskApp.LOG, "Scrolled settings to keep the focused field above the keyboard");
            return true;
        }

        /** 视口高度变化（键盘或窗口属性）后重新钉住，避免被夹到 0。 */
        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (oldh > 0 && h != oldh) keepPosition();
        }

        /** 布局阶段若 scrollY 偏离锚点，内容够高就立刻写回。 */
        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (moveAllowed()) return;
            int max = maxScrollY();
            if (max >= 0 && anchorY <= max && getScrollY() != anchorY) {
                int before = getScrollY();
                super.scrollTo(getScrollX(), anchorY);
                if (Math.abs(before - getScrollY()) >= dp(8)) {
                    Log.i(KioskApp.LOG, "Restored settings scrollY from " + before + " to " + getScrollY());
                }
            } else if (max >= 0 && anchorY > max) {
                keepPosition();
            }
        }

        /** 开始拖动后不再把这次手势写回旧锚点。 */
        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            boolean intercepted = super.onInterceptTouchEvent(ev);
            if (intercepted && ev.getActionMasked() == MotionEvent.ACTION_MOVE) {
                userDrag = true;
                flinging = false;
                holdAnchor = false;
            }
            return intercepted;
        }

        /** 手指滑动更新锚点；松手后若没有惯性，停住的位置就是新锚点。 */
        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            int action = ev.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE) {
                userDrag = true;
                flinging = false;
                holdAnchor = false;
            }
            boolean handled = super.onTouchEvent(ev);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                userDrag = false;
                if (!flinging) anchorY = getScrollY();
            }
            return handled;
        }

        /** 惯性滑动过程中每一帧都更新锚点，结束后不再接受程序滚动。 */
        @Override
        public void fling(int velocityY) {
            flinging = true;
            flingStable = Integer.MIN_VALUE;
            super.fling(velocityY);
        }

        /** 惯性停稳后清掉标记。程序触发的平滑滚动不会把 flinging 设为真。 */
        @Override
        public void computeScroll() {
            int before = getScrollY();
            super.computeScroll();
            if (!flinging) return;
            anchorY = getScrollY();
            if (getScrollY() == before) {
                if (flingStable == before) flinging = false;
                else flingStable = before;
            } else {
                flingStable = Integer.MIN_VALUE;
            }
        }

        /**
         * 触摸和惯性会走到这里，并且可能直接改 scrollY，不经过 scrollTo。
         * 其余来源（焦点平滑滚动）直接丢掉。
         */
        @Override
        protected void onOverScrolled(int scrollX, int scrollY, boolean clampedX, boolean clampedY) {
            if (moveAllowed()) {
                super.onOverScrolled(scrollX, scrollY, clampedX, clampedY);
                anchorY = getScrollY();
                return;
            }
            if (Math.abs(scrollY - anchorY) >= dp(8)) {
                long now = SystemClock.uptimeMillis();
                if (now - lastAutoScrollLogMs > 1000) {
                    lastAutoScrollLogMs = now;
                    Log.i(KioskApp.LOG, "Ignored settings autoscroll toward " + scrollY);
                }
            }
        }

        /** 布局夹紧和 requestChildFocus 都会调用这里。非用户滚动一律写回锚点。 */
        @Override
        public void scrollTo(int x, int y) {
            int target = moveAllowed() ? y : anchorY;
            super.scrollTo(x, target);
            if (moveAllowed()) anchorY = getScrollY();
        }

        /** 焦点要把某个子矩形滚进屏幕时默认拒绝。主动 focusField 时放行一次。 */
        @Override
        public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
            if (!allowOnce) return false;
            return super.requestChildRectangleOnScreen(child, rectangle, immediate);
        }

        /** 计算滚动量时默认返回 0，避免开关、顶部输入框获焦把列表拉开。 */
        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            if (!allowOnce) return 0;
            return super.computeScrollDeltaToGetChildRectOnScreen(rect);
        }
    }

    /** 面板本体：横向向左拖动超过阈值时关闭，纵向滚动交给内部滚动视图。 */
    private final class PanelFrame extends FrameLayout {
        private final int slop;
        private final int cornerRadius;
        private final Path clipPath = new Path();
        private final Outline clipProbe = new Outline();
        private boolean manualClip;
        private float downX;
        private float downY;
        private boolean dragging;
        /** 这次手势从百分比拖动条开始，不要当成关闭面板的左滑。 */
        private boolean sliderGesture;

        /** 读取系统触摸阈值，并按右侧圆角裁剪子视图。 */
        PanelFrame(Context context, int cornerRadius) {
            super(context);
            this.cornerRadius = cornerRadius;
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
            setOutlineProvider(new RightCornersOutline(cornerRadius));
            // 轮廓能裁剪时由系统裁；不能裁时仍由 draw 里的路径兜底。
            setClipToOutline(true);
        }

        /** 尺寸变化后重建裁剪路径；系统轮廓不能裁时，绘制阶段再裁一次。 */
        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            RightCornersOutline.fill(clipPath, w, h, cornerRadius);
            manualClip = true;
            if (w > 0 && h > 0 && getOutlineProvider() != null) {
                getOutlineProvider().getOutline(this, clipProbe);
                manualClip = !clipProbe.canClip();
            }
        }

        /** 系统轮廓裁不动时，按右侧圆角裁掉背景和全部子视图。 */
        @Override
        public void draw(Canvas canvas) {
            if (!manualClip || clipPath.isEmpty()) {
                super.draw(canvas);
                return;
            }
            int save = canvas.save();
            canvas.clipPath(clipPath);
            super.draw(canvas);
            canvas.restoreToCount(save);
        }

        /** 识别明显向左的横向拖动后接管手势。 */
        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    dragging = false;
                    sliderGesture = touchHitsPercent(ev);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (sliderGesture) return false;
                    float dx = ev.getRawX() - downX;
                    float dy = ev.getRawY() - downY;
                    if (dx < -slop * 2 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        dragging = true;
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    sliderGesture = false;
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
                    sliderGesture = touchHitsPercent(ev);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    // 拖动条上的移动留给 SeekBar，这里不要平移面板
                    if (sliderGesture) return true;
                    // 按在面板空白处时不会经过拦截判断，这里补判一次
                    if (!dragging && dx < -slop * 2 && Math.abs(dx) > Math.abs(ev.getRawY() - downY) * 1.5f) {
                        dragging = true;
                    }
                    if (dragging) setTranslationX(Math.min(0, dx));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!sliderGesture && dragging && dx < -dp(80)) hide();
                    else animate().translationX(0).setDuration(150).start();
                    dragging = false;
                    sliderGesture = false;
                    return true;
                default:
                    return true;
            }
        }
    }

    /** 轮廓只圆右上、右下，左边保持直角。 */
    private static final class RightCornersOutline extends ViewOutlineProvider {
        private final int radius;
        private static boolean loggedUnclippable;

        /** @param radius 右侧圆角半径，像素 */
        RightCornersOutline(int radius) {
            this.radius = radius;
        }

        /** 写入只含右侧圆角的路径。宽高无效时清空。 */
        static void fill(Path path, int width, int height, float radius) {
            path.rewind();
            if (width <= 0 || height <= 0 || radius <= 0f) return;
            float r = Math.min(radius, Math.min(width, height) / 2f);
            path.addRoundRect(0, 0, width, height,
                    new float[] {0f, 0f, r, r, r, r, 0f, 0f}, Path.Direction.CW);
        }

        /** 优先用路径；系统拒绝路径裁剪时，改用向左伸出的圆角矩形，视图内仍只有右侧圆角。 */
        @Override
        public void getOutline(View view, Outline outline) {
            int w = view.getWidth();
            int h = view.getHeight();
            if (w <= 0 || h <= 0) {
                outline.setEmpty();
                return;
            }
            Path path = new Path();
            fill(path, w, h, radius);
            try {
                outline.setPath(path);
            } catch (IllegalArgumentException e) {
                Log.w(KioskApp.LOG, "Right-rounded outline path was rejected", e);
                outline.setRoundRect(-radius, 0, w, h, radius);
                return;
            }
            // Android 12 及更早的 clipToOutline 不裁路径，轮廓会退回矩形，圆弧外仍会漏出子视图。
            if (!outline.canClip()) {
                if (!loggedUnclippable) {
                    loggedUnclippable = true;
                    Log.i(KioskApp.LOG, "Path outline cannot clip; using an offset round rect");
                }
                outline.setRoundRect(-radius, 0, w, h, radius);
            }
        }
    }
}
