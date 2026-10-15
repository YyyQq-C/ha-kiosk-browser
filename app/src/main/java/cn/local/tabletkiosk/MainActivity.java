package cn.local.tabletkiosk;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import java.util.function.Consumer;

/** 全屏显示 HA 页面：空闲刷新、失败重试、观看与待机亮度、摄像头亮屏和左边缘设置。 */
public final class MainActivity extends ComponentActivity
        implements KioskHub.Controller, EdgeSwipeLayout.Listener, SettingsPanel.Host, CameraMotion.Listener {
    private static final long TICK_MS = 1000;
    private static final long WAKE_LOCK_MS = 5000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Consumer<KioskConfig> configListener = this::applyConfig;
    private final Runnable statusListener = this::onStatusChanged;
    private final Runnable tick = new Runnable() {
        /** 每秒检查降亮度、“有人”状态和空闲刷新。 */
        @Override
        public void run() {
            onTick();
            handler.postDelayed(this, TICK_MS);
        }
    };
    private final Runnable retry = this::retryLoad;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        /** 系统亮屏或息屏时更新“亮屏”状态。 */
        @Override
        public void onReceive(Context context, Intent intent) {
            updateInteractive();
        }
    };
    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                Log.i(KioskApp.LOG, "Camera permission granted=" + granted);
                syncCamera();
                refreshPanel();
                maybePromptBattery();
            });
    private final ActivityResultLauncher<Intent> systemScreen =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                systemScreenOpen = false;
                refreshPanel();
            });

    private KioskHub hub;
    private KioskConfig config;
    private PowerManager power;
    private EdgeSwipeLayout edge;
    private SettingsPanel settings;
    private WebView web;
    private PresenceTracker presence;
    private IdleReload idle;
    private CameraMotion camera;
    private boolean started;
    private boolean retryPending;
    private boolean mainFrameError;
    private boolean systemScreenOpen;

    /** 搭建全屏界面、加载首页并启动后台服务。 */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hub = KioskHub.get();
        config = hub.config();
        power = getSystemService(PowerManager.class);
        configureWindow();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        edge = new EdgeSwipeLayout(this);
        edge.setListener(this);
        root.addView(edge, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        settings = new SettingsPanel(this, hub, this);
        root.addView(settings, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        long now = SystemClock.elapsedRealtime();
        presence = new PresenceTracker(config.dimDelaySeconds, config.presenceHoldSeconds, now);
        idle = new IdleReload(config.idleReloadHours, now);
        camera = new CameraMotion(this, this);
        createWebView();
        web.loadUrl(config.homeUrl);

        hub.setController(this);
        hub.addConfigListener(configListener);
        hub.addStatusListener(statusListener);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            /** 返回键：先关设置，再网页后退，不退出应用。 */
            @Override
            public void handleOnBackPressed() {
                if (settings.isOpen()) settings.hide();
                else if (web.canGoBack()) web.goBack();
            }
        });
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        ContextCompat.registerReceiver(this, screenReceiver, screen, ContextCompat.RECEIVER_NOT_EXPORTED);

        startKioskService();
        DeviceOwnerPolicy.applyPolicies(this);
        refreshOwnerStatus();
        applyBrightness();
        firstRunPrompts();
    }

    /** 开始计时并按需打开摄像头。 */
    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        handler.removeCallbacks(tick);
        handler.post(tick);
        updateInteractive();
        syncCamera();
    }

    /** 每次回到前台重新隐藏系统栏并同步锁定模式。 */
    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        syncLockTask();
        refreshOwnerStatus();
    }

    /** 离开前台时停止计时和摄像头，并把登录 Cookie 写入磁盘。 */
    @Override
    protected void onStop() {
        started = false;
        handler.removeCallbacks(tick);
        camera.stop();
        CookieManager.getInstance().flush();
        super.onStop();
    }

    /** 释放网页、摄像头和监听。 */
    @Override
    protected void onDestroy() {
        hub.setController(null);
        hub.removeConfigListener(configListener);
        hub.removeStatusListener(statusListener);
        try {
            unregisterReceiver(screenReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(KioskApp.LOG, "Screen receiver was not registered", e);
        }
        camera.release();
        handler.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** 窗口重新获得焦点时再隐藏一次系统栏，防止键盘或弹窗后露出。 */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    // ---------- 窗口与网页 ----------

    /** 保持常亮、允许显示在锁屏上方，并让内容铺满刘海区域。 */
    private void configureWindow() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(lp);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    }

    /** 隐藏状态栏和导航栏，从边缘滑动只临时显示。 */
    private void hideSystemBars() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    /** 创建或重建网页视图；网页渲染进程崩溃后也走这里。 */
    private void createWebView() {
        if (web != null) {
            edge.removeView(web);
            web.destroy();
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new KioskWebClient());
        web.setWebChromeClient(new WebChromeClient());
        edge.addView(web, 0, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        edge.setTapTarget(web);
    }

    /** 网页加载回调：记录当前网址、失败重试和渲染进程恢复。 */
    private final class KioskWebClient extends WebViewClient {
        /** 只允许网页内的 http/https 跳转，拦截拨号、外部应用等链接。 */
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String scheme = request.getUrl().getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return false;
            Log.w(KioskApp.LOG, "Blocked navigation to non-web scheme: " + scheme);
            return true;
        }

        /** 新一次主页面加载开始，清除失败标记。 */
        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            mainFrameError = false;
        }

        /** 加载完成后上报当前网址；成功时取消待执行的重试。 */
        @Override
        public void onPageFinished(WebView view, String url) {
            String current = url == null ? "" : url;
            hub.updateStatus(b -> {
                b.url = current;
                return b;
            });
            if (!mainFrameError) cancelRetry();
        }

        /** 主页面网络错误时安排重试。 */
        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                onLoadFailure("Main frame load failed: code=" + error.getErrorCode() + " " + error.getDescription());
            }
        }

        /** 主页面服务器 5xx 错误时安排重试。 */
        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame() && response.getStatusCode() >= 500) {
                onLoadFailure("Main frame HTTP error: " + response.getStatusCode());
            }
        }

        /** 证书错误一律拒绝，不忽略校验。 */
        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            Log.e(KioskApp.LOG, "SSL error rejected: primary=" + error.getPrimaryError());
            handler.cancel();
        }

        /** 渲染进程崩溃或被回收时重建网页，避免整个应用退出。 */
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            Log.e(KioskApp.LOG, "WebView renderer gone, crashed=" + detail.didCrash());
            if (view == web) {
                createWebView();
                web.loadUrl(config.homeUrl);
            }
            return true;
        }
    }

    /** 记录失败并在设定秒数后重试；秒数为 0 时不重试。 */
    private void onLoadFailure(String reason) {
        mainFrameError = true;
        Log.w(KioskApp.LOG, reason);
        if (config.retrySeconds <= 0 || retryPending) return;
        retryPending = true;
        handler.postDelayed(retry, config.retrySeconds * 1000L);
    }

    /** 执行一次重试：重载失败的页面，没有网址时回首页。 */
    private void retryLoad() {
        retryPending = false;
        String url = web.getUrl();
        Log.i(KioskApp.LOG, "Retrying page load");
        if (url == null || url.isEmpty()) web.loadUrl(config.homeUrl);
        else web.reload();
    }

    /** 取消待执行的重试。 */
    private void cancelRetry() {
        handler.removeCallbacks(retry);
        retryPending = false;
    }

    // ---------- 计时、亮度与状态 ----------

    /** 定时检查：降亮度、结束“有人”状态、空闲刷新。 */
    private void onTick() {
        long now = SystemClock.elapsedRealtime();
        if (presence.tick(now)) applyBrightness();
        if (!settings.isOpen() && idle.shouldReload(now)) {
            Log.i(KioskApp.LOG, "Idle reload triggered after " + config.idleReloadHours + " h without touch");
            reload();
        }
    }

    /** 按观看或待机状态设置本窗口亮度，不改系统全局亮度。 */
    private void applyBrightness() {
        boolean watching = presence.watching();
        int percent = watching ? config.watchBrightness : config.standbyBrightness;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        float value = percent / 100f;
        if (lp.screenBrightness != value) {
            lp.screenBrightness = value;
            getWindow().setAttributes(lp);
        }
        boolean someone = presence.presence();
        hub.updateStatus(b -> {
            b.brightness = percent;
            b.watching = watching;
            b.presence = someone;
            return b;
        });
    }

    /** 读取系统屏幕是否点亮，用于“亮屏”状态。 */
    private void updateInteractive() {
        boolean on = power != null && power.isInteractive();
        hub.updateStatus(b -> {
            b.interactive = on;
            return b;
        });
    }

    /** 更新设备所有者和锁定模式状态。 */
    private void refreshOwnerStatus() {
        boolean owner = DeviceOwnerPolicy.isDeviceOwner(this);
        boolean locked = DeviceOwnerPolicy.inLockTask(this);
        hub.updateStatus(b -> {
            b.deviceOwner = owner;
            b.lockTask = locked;
            return b;
        });
    }

    /** 状态变化时刷新已打开的设置面板。 */
    private void onStatusChanged() {
        refreshPanel();
    }

    /** 设置面板打开时刷新它的状态区。 */
    private void refreshPanel() {
        if (settings != null && settings.isOpen()) settings.refreshStatus();
    }

    /** 新配置立即生效。 */
    private void applyConfig(KioskConfig next) {
        KioskConfig old = config;
        config = next;
        long now = SystemClock.elapsedRealtime();
        presence.configure(next.dimDelaySeconds, next.presenceHoldSeconds);
        presence.tick(now);
        idle.setHours(next.idleReloadHours);
        if (next.retrySeconds <= 0) cancelRetry();
        if (!old.homeUrl.equals(next.homeUrl)) {
            Log.i(KioskApp.LOG, "Home URL changed, loading new start page");
            web.loadUrl(next.homeUrl);
        }
        applyBrightness();
        syncCamera();
        syncLockTask();
    }

    /** 按配置和权限打开或关闭摄像头检测。 */
    private void syncCamera() {
        boolean want = started && config.motionEnabled && config.motionSensitivity > 0 && cameraGranted();
        if (want) {
            camera.start(config.motionSensitivity);
        } else {
            camera.stop();
            if (presence.clearPresence()) applyBrightness();
        }
    }

    /** 按配置进入或退出锁定模式；打开系统界面期间暂不锁定。 */
    private void syncLockTask() {
        if (systemScreenOpen) return;
        DeviceOwnerPolicy.syncLockTask(this, config.lockTaskEnabled);
        // 锁定模式切换是异步的，稍后再读取状态
        handler.postDelayed(this::refreshOwnerStatus, 500);
    }

    // ---------- 触摸、摄像头与远程命令 ----------

    /** 触摸重置空闲刷新和无人计时。 */
    @Override
    public void onUserTouch() {
        long now = SystemClock.elapsedRealtime();
        idle.onInteraction(now);
        if (presence.onActivity(now)) applyBrightness();
    }

    /** 左边缘滑出设置。 */
    @Override
    public void onOpenSettings() {
        edge.setEdgeEnabled(false);
        settings.show();
    }

    /** 摄像头检测到画面变化：标记有人并升到观看亮度。 */
    @Override
    public void onMotion() {
        presence.onMotion(SystemClock.elapsedRealtime());
        applyBrightness();
    }

    /** 清缓存并重载当前页，保留 Cookie 和 localStorage。 */
    @Override
    public void reload() {
        cancelRetry();
        Log.i(KioskApp.LOG, "Reloading dashboard with HTTP cache cleared");
        web.clearCache(true);
        String url = web.getUrl();
        if (url == null || url.isEmpty()) web.loadUrl(config.homeUrl);
        else web.reload();
    }

    /** 升到观看亮度；系统屏幕熄灭时短暂持锁点亮。 */
    @Override
    @SuppressWarnings("deprecation")
    public void wake() {
        if (power != null && !power.isInteractive()) {
            PowerManager.WakeLock lock = power.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "TabletKiosk:wake");
            lock.acquire(WAKE_LOCK_MS);
        }
        presence.onActivity(SystemClock.elapsedRealtime());
        applyBrightness();
    }

    // ---------- 设置面板操作 ----------

    /** 设置面板中的“立即刷新”。 */
    @Override
    public void reloadNow() {
        settings.hide();
        reload();
    }

    /** 设置面板中的“回到首页”。 */
    @Override
    public void goHome() {
        settings.hide();
        cancelRetry();
        web.loadUrl(config.homeUrl);
    }

    /** 请求摄像头权限。 */
    @Override
    public void requestCameraPermission() {
        cameraPermission.launch(Manifest.permission.CAMERA);
    }

    /** 打开系统的电池优化白名单确认框。 */
    @Override
    public void requestBatteryExemption() {
        openSystemScreen(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri()));
    }

    /** 打开“显示在其他应用上层”授权页，非设备所有者时开机拉起界面需要它。 */
    @Override
    public void requestOverlayPermission() {
        openSystemScreen(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()));
    }

    /** 打开系统设置。 */
    @Override
    public void openSystemSettings() {
        openSystemScreen(new Intent(Settings.ACTION_SETTINGS));
    }

    /** 设置面板关闭后恢复边缘手势和全屏。 */
    @Override
    public void onPanelClosed() {
        edge.setEdgeEnabled(true);
        hideSystemBars();
    }

    /** 是否已有摄像头权限。 */
    @Override
    public boolean cameraGranted() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    /** 是否已在电池优化白名单。 */
    @Override
    public boolean batteryExempt() {
        return power != null && power.isIgnoringBatteryOptimizations(getPackageName());
    }

    /** 是否已允许显示在其他应用上层。 */
    @Override
    public boolean overlayGranted() {
        return Settings.canDrawOverlays(this);
    }

    // ---------- 启动辅助 ----------

    /** 启动保持 MQTT 和局域网接口在线的前台服务。 */
    private void startKioskService() {
        try {
            startForegroundService(new Intent(this, KioskService.class));
        } catch (IllegalStateException e) {
            Log.e(KioskApp.LOG, "Failed to start kiosk service", e);
        }
    }

    /** 首次运行：未授权时请求摄像头权限，随后提示一次电池优化白名单。 */
    private void firstRunPrompts() {
        if (config.motionEnabled && !cameraGranted()) {
            requestCameraPermission();
        } else {
            maybePromptBattery();
        }
    }

    /** 仅在首次运行且未加入白名单时提示一次。 */
    private void maybePromptBattery() {
        if (batteryExempt() || hub.store().batteryPrompted()) return;
        hub.store().markBatteryPrompted();
        requestBatteryExemption();
    }

    /** 打开系统界面；锁定模式下系统会拦截，先提示关闭锁定。 */
    private void openSystemScreen(Intent intent) {
        if (DeviceOwnerPolicy.inLockTask(this)) {
            Toast.makeText(this, "锁定模式下不能打开系统界面，请先关闭锁定模式并保存", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            systemScreenOpen = true;
            systemScreen.launch(intent);
        } catch (ActivityNotFoundException e) {
            systemScreenOpen = false;
            Log.e(KioskApp.LOG, "System screen not available: " + intent.getAction(), e);
            Toast.makeText(this, "系统不支持这个设置页面", Toast.LENGTH_SHORT).show();
        }
    }

    /** 本应用的 package: 地址。 */
    private Uri packageUri() {
        return Uri.parse("package:" + getPackageName());
    }
}
