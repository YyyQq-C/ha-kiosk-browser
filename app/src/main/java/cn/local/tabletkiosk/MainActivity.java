package cn.local.tabletkiosk;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.text.InputType;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ServiceWorkerController;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import java.io.ByteArrayOutputStream;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** 全屏显示 HA 页面：空闲刷新、失败重试、观看与待机亮度、摄像头亮屏和左边缘设置。 */
public final class MainActivity extends ComponentActivity
        implements KioskHub.Controller, EdgeSwipeLayout.Listener, SettingsPanel.Host, CameraMotion.Listener {
    private static final long TICK_MS = 1000;
    private static final long WAKE_LOCK_MS = 5000;
    private static final long CACHE_PURGE_TIMEOUT_MS = 1500;
    private static final long NOTICE_MS = 8000;
    private static final String LOCK_JS = "try{var s=document.getElementById('kiosk-lock');"
            + "if(!s){s=document.createElement('style');s.id='kiosk-lock';"
            + "(document.head||document.documentElement).appendChild(s);}"
            + "s.textContent='*{user-select:none!important;-webkit-user-select:none!important;"
            + "-webkit-touch-callout:none!important;-webkit-user-drag:none!important}';}catch(e){}";
    /** 在页面里注销 Service Worker 并删除 Cache Storage，完成后回调原生。 */
    private static final String PURGE_JS = "(function(){var done=false;function finish(){if(done)return;done=true;"
            + "try{KioskCache.onCleared();}catch(e){}}"
            + "try{var jobs=[];if(navigator.serviceWorker&&navigator.serviceWorker.getRegistrations){"
            + "jobs.push(navigator.serviceWorker.getRegistrations().then(function(rs){return Promise.all("
            + "rs.map(function(r){return r.unregister();}));}));}"
            + "if(window.caches&&caches.keys){jobs.push(caches.keys().then(function(ks){return Promise.all("
            + "ks.map(function(k){return caches.delete(k);}));}));}"
            + "Promise.all(jobs).then(finish,finish);}catch(e){finish();}setTimeout(finish,1200);})();";

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
                startKioskService();
                refreshPanel();
                if (granted) requestWifiPermission();
                else maybePromptBattery();
            });
    private final ActivityResultLauncher<String[]> wifiPermission =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), granted -> {
                Log.i(KioskApp.LOG, "Wi-Fi permission result received");
                publishDeviceInfo();
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
    private TextView notice;
    private TextToSpeech tts;
    private MediaPlayer player;
    private FrameLayout pinGate;
    private EditText pinInput;
    private int infoTick;
    private CpuSample lastCpu;
    private final DailyReload dailyReload = new DailyReload();
    private final PageWatchdog watchdog = new PageWatchdog();
    private final Runnable hideNotice = () -> {
        if (notice != null) notice.setVisibility(View.GONE);
    };
    private PresenceTracker presence;
    private IdleReload idle;
    private CameraMotion camera;
    private boolean started;
    private boolean retryPending;
    private boolean mainFrameError;
    private boolean systemScreenOpen;
    private boolean purgeOnNextFinish;
    private int freshGeneration;
    private String pendingFreshUrl;

    /** 按已保存的语言创建界面。 */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocales.wrap(newBase));
    }

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
        notice = buildNotice(root);
        pinGate = buildPinGate(root);
        setContentView(root);
        if (hub.consumeReopenSettings()) {
            settings.post(() -> {
                edge.setEdgeEnabled(false);
                settings.show();
                syncCamera();
            });
        }
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) tts.setLanguage(Locale.getDefault());
            else Log.w(KioskApp.LOG, "Text to speech unavailable, status=" + status);
        });
        publishDeviceInfo();
        // 第一次只有 CPU 计数，几秒后再采样才能算出占用
        handler.postDelayed(this::publishDeviceInfo, 5000);

        long now = SystemClock.elapsedRealtime();
        presence = new PresenceTracker(config.dimDelaySeconds, config.presenceHoldSeconds, now);
        idle = new IdleReload(config.idleReloadHours, now);
        camera = new CameraMotion(this, this);
        createWebView();
        // 安装新版本不会清空上次的网页缓存，启动时丢掉旧前端再加载
        purgeOnNextFinish = true;
        loadFresh(config.homeUrl);

        hub.setController(this);
        hub.addConfigListener(configListener);
        hub.addStatusListener(statusListener);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            /** 返回键：先关设置，再网页后退，不退出应用。 */
            @Override
            public void handleOnBackPressed() {
                if (pinGate != null && pinGate.getVisibility() == View.VISIBLE) hidePinGate();
                else if (settings.isOpen()) settings.hide();
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
        applyVolume();
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

    /** 息屏后仍保留计时和人体检测，以便到点刷新、早晨亮屏和人来亮屏。 */
    @Override
    protected void onStop() {
        started = false;
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
        if (tts != null) tts.shutdown();
        releasePlayer();
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
        ServiceWorkerController.getInstance().getServiceWorkerWebSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
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
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        // 不使用 HTTP 缓存，避免 HA 前端更新后仍显示旧界面
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        CookieManager.getInstance().setAcceptCookie(true);
        web.addJavascriptInterface(new CacheBridge(), "KioskCache");
        web.setLongClickable(false);
        web.setHapticFeedbackEnabled(false);
        web.setOnLongClickListener(v -> true);
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
            watchdog.onPageStarted(SystemClock.elapsedRealtime());
        }

        /** 加载完成后上报当前网址；成功时取消待执行的重试。 */
        @Override
        public void onPageFinished(WebView view, String url) {
            String current = url == null ? "" : url;
            hub.updateStatus(b -> {
                b.url = current;
                return b;
            });
            if (!mainFrameError) {
                cancelRetry();
                watchdog.onPageFinished(SystemClock.elapsedRealtime());
            }
            view.evaluateJavascript(LOCK_JS, null);
            if (purgeOnNextFinish) {
                purgeOnNextFinish = false;
                purgeThenLoad(current.isEmpty() ? config.homeUrl : current);
            }
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
                purgeOnNextFinish = true;
                loadFresh(config.homeUrl);
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

    /** 执行一次重试：不走缓存重新打开失败的页面，没有网址时回首页。 */
    private void retryLoad() {
        retryPending = false;
        String url = web.getUrl();
        Log.i(KioskApp.LOG, "Retrying page load");
        loadFresh(url == null || url.isEmpty() ? config.homeUrl : url);
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
        Calendar calendar = Calendar.getInstance();
        presence.tick(now);
        applyBrightness();
        if (!settings.isOpen() && pinClosed() && dailyReload.due(config.dailyReloadEnabled, config.dailyReloadHour,
                config.dailyReloadMinute, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE),
                calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR))) {
            Log.i(KioskApp.LOG, "Daily reload triggered at " + config.dailyReloadHour + ":" + config.dailyReloadMinute);
            reload();
        }
        applySleep(now, calendar.get(Calendar.HOUR_OF_DAY));
        checkWatchdog(now);
        if (++infoTick >= 60) {
            infoTick = 0;
            publishDeviceInfo();
        }
        if (!settings.isOpen() && idle.shouldReload(now)) {
            Log.i(KioskApp.LOG, "Idle reload triggered after " + config.idleReloadHours + " h without touch");
            reload();
        }
    }

    /** 按观看或待机状态设置本窗口亮度，不改系统全局亮度。 */
    private void applyBrightness() {
        boolean watching = presence.watching();
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int percent = watching ? BrightnessSchedule.watchingBrightness(config, hour) : config.standbyBrightness;
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
            loadFresh(next.homeUrl);
        }
        applyBrightness();
        applyVolume();
        syncCamera();
        syncLockTask();
    }

    /** 按配置和权限打开或关闭摄像头检测。 */
    private void syncCamera() {
        // 设置打开时停掉摄像头，系统才不会在左上角留着绿色的隐私指示点
        boolean want = settings != null && !settings.isOpen()
                && config.motionEnabled && config.motionSensitivity > 0 && cameraGranted();
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

    /** 左边缘滑出设置；设了密码时先核对密码。 */
    @Override
    public void onOpenSettings() {
        edge.setEdgeEnabled(false);
        if (config.settingsPin != null && !config.settingsPin.isEmpty()) showPinGate();
        else {
            settings.show();
            syncCamera();
        }
    }

    /** 摄像头检测到画面变化：标记有人；屏幕已熄时点亮。 */
    @Override
    public void onMotion() {
        boolean dark = power != null && !power.isInteractive();
        presence.onMotion(SystemClock.elapsedRealtime());
        if (dark) wake();
        else applyBrightness();
    }

    /** 清掉网页缓存和 Service Worker 后重新取当前页，保留 Cookie 和 localStorage。 */
    @Override
    public void reload() {
        cancelRetry();
        Log.i(KioskApp.LOG, "Reloading dashboard with HTTP cache and service worker cleared");
        String url = web.getUrl();
        purgeThenLoad(url == null || url.isEmpty() ? config.homeUrl : url);
    }

    /** 不经过缓存加载地址；页面还没打开时先正常打开，加载完成后再清一次旧前端。 */
    private void loadFresh(String url) {
        web.clearCache(true);
        web.loadUrl(url, noCacheHeaders());
    }

    /** 在当前页删除 Cache Storage 和 Service Worker，然后重新加载。 */
    private void purgeThenLoad(String url) {
        int gen = ++freshGeneration;
        pendingFreshUrl = url;
        web.clearCache(true);
        String current = web.getUrl();
        if (current == null || current.isEmpty() || current.startsWith("about:")) {
            finishFresh(gen);
            return;
        }
        web.evaluateJavascript(PURGE_JS, null);
        handler.postDelayed(() -> finishFresh(gen), CACHE_PURGE_TIMEOUT_MS);
    }

    /** 清理完成后加载待打开的地址；同一次清理只加载一次。 */
    private void finishFresh(int generation) {
        if (generation != freshGeneration) return;
        freshGeneration++;
        String url = pendingFreshUrl;
        pendingFreshUrl = null;
        if (url == null || url.isEmpty()) return;
        Log.i(KioskApp.LOG, "Loading page without cache");
        web.loadUrl(url, noCacheHeaders());
    }

    /** 告诉服务器不要返回缓存副本。 */
    private static Map<String, String> noCacheHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-cache");
        headers.put("Pragma", "no-cache");
        return headers;
    }

    /** 供页面脚本在缓存清理结束后回调。 */
    private final class CacheBridge {
        /** 页面侧清理完成。 */
        @JavascriptInterface
        public void onCleared() {
            int generation = freshGeneration;
            handler.post(() -> finishFresh(generation));
        }
    }

    /** 升到观看亮度；系统屏幕熄灭时短暂持锁点亮。 */
    @Override
    @SuppressWarnings("deprecation")
    public void wake() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (power != null && !power.isInteractive()) {
            PowerManager.WakeLock lock = power.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "TabletKiosk:wake");
            lock.acquire(WAKE_LOCK_MS);
            Intent intent = new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
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

    /** 回到已保存的首页。设置面板和远程命令共用。 */
    @Override
    public void goHome() {
        settings.hide();
        cancelRetry();
        purgeThenLoad(config.homeUrl);
    }

    /** 打开指定网页，不改已保存的首页。 */
    @Override
    public void openUrl(String url) {
        if (url == null || url.isEmpty()) return;
        Log.i(KioskApp.LOG, "Opening requested page");
        cancelRetry();
        loadFresh(url);
    }

    /** 熄灭屏幕。不是设备所有者时只记录日志。 */
    @Override
    public void screenOff() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        DeviceOwnerPolicy.screenOff(this);
    }

    /** 截取当前窗口并交给 MQTT 发布。 */
    @Override
    public void captureScreen() {
        View decor = getWindow().getDecorView();
        int width = decor.getWidth();
        int height = decor.getHeight();
        if (width <= 0 || height <= 0) {
            Log.w(KioskApp.LOG, "Screenshot skipped: window has no size");
            return;
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        PixelCopy.request(getWindow(), bitmap, result -> {
            if (result != PixelCopy.SUCCESS) {
                Log.w(KioskApp.LOG, "Screenshot failed, code=" + result);
                bitmap.recycle();
                return;
            }
            Bitmap scaled = scaleDown(bitmap, 1280);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, out);
            if (scaled != bitmap) scaled.recycle();
            bitmap.recycle();
            hub.submitScreenshot(out.toByteArray());
        }, handler);
    }

    /** 在画面顶部显示一条通知，几秒后消失。 */
    @Override
    public void showNotice(String text) {
        if (notice == null || text == null || text.isEmpty()) return;
        notice.setText(text);
        notice.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideNotice);
        handler.postDelayed(hideNotice, NOTICE_MS);
    }

    /** 用系统语音引擎朗读。 */
    @Override
    public void speak(String text) {
        if (tts == null || text == null || text.isEmpty()) return;
        int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kiosk-notice");
        if (result == TextToSpeech.ERROR) Log.w(KioskApp.LOG, "Text to speech failed to start");
    }

    /** 创建顶部通知条，默认隐藏。 */
    private TextView buildNotice(FrameLayout root) {
        TextView view = new TextView(this);
        view.setTextColor(Color.WHITE);
        view.setTextSize(18);
        view.setGravity(Gravity.CENTER);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad, pad, pad);
        view.setBackgroundColor(Color.argb(230, 24, 48, 57));
        view.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        lp.topMargin = pad;
        lp.leftMargin = pad;
        lp.rightMargin = pad;
        root.addView(view, lp);
        return view;
    }

    /** 把截图长边缩到不超过上限。 */
    private static Bitmap scaleDown(Bitmap source, int maxEdge) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longEdge = Math.max(width, height);
        if (longEdge <= maxEdge) return source;
        float scale = maxEdge / (float) longEdge;
        return Bitmap.createScaledBitmap(source, Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)), true);
    }

    /** 上报本机地址、型号和版本，内容没变时不触发状态通知。 */
    private void publishDeviceInfo() {
        String ip = NetUtil.localIpv4(this);
        String version = BuildConfig.VERSION_NAME;
        String model = Build.MANUFACTURER + " " + Build.MODEL;
        String android = Build.VERSION.RELEASE;
        CpuSample cpuNow = CpuSample.read();
        int cpu = CpuSample.percent(lastCpu, cpuNow);
        if (cpuNow != null) lastCpu = cpuNow;
        SystemPressure pressure = SystemPressure.read(this);
        hub.updateStatus(b -> {
            b.ip = ip;
            b.appVersion = version;
            b.model = model;
            b.androidVersion = android;
            b.volume = currentVolume();
            NetUtil.WifiSnapshot wifi = NetUtil.wifi(this);
            b.wifiSsid = wifi.ssid;
            b.wifiRssi = wifi.rssi;
            b.cpuPercent = cpu;
            b.memoryPercent = pressure.memoryPercent;
            b.memoryUsedMb = pressure.memoryUsedMb;
            b.memoryTotalMb = pressure.memoryTotalMb;
            b.memoryLow = pressure.memoryLow;
            b.storagePercent = pressure.storagePercent;
            b.appMemoryMb = pressure.appMemoryMb;
            return b;
        });
    }

    /** 夜间时段内无人后熄屏；离开夜间时段时重新点亮。 */
    private void applySleep(long now, int hour) {
        if (!config.sleepEnabled) return;
        boolean asleep = SleepWindow.contains(config.sleepStartHour, config.sleepEndHour, hour);
        boolean interactive = power != null && power.isInteractive();
        if (asleep && interactive && presence.quiet(now) && settings != null && !settings.isOpen() && pinClosed()
                && hub.status().deviceOwner) {
            Log.i(KioskApp.LOG, "Sleep schedule turning the screen off");
            screenOff();
        } else if (!asleep && !interactive) {
            Log.i(KioskApp.LOG, "Sleep schedule turning the screen on");
            wake();
        }
    }

    /** 页面加载或脚本超时后清缓存回到首页。 */
    private void checkWatchdog(long now) {
        if (config.watchdogSeconds <= 0 || settings.isOpen() || !pinClosed() || web == null) return;
        if (watchdog.expired(now, config.watchdogSeconds)) {
            Log.w(KioskApp.LOG, "Page watchdog reloading home");
            watchdog.onPageStarted(now);
            purgeThenLoad(config.homeUrl);
            return;
        }
        if (watchdog.shouldPing(now, config.watchdogSeconds)) {
            watchdog.onPingSent(now);
            web.evaluateJavascript("1", value -> watchdog.onPingDone(SystemClock.elapsedRealtime()));
        }
    }

    /** 把保存的百分比写到媒体音量。 */
    private void applyVolume() {
        AudioManager audio = getSystemService(AudioManager.class);
        if (audio == null) return;
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (max <= 0) return;
        int index = Math.round(config.volume / 100f * max);
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != index) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0);
        }
        int percent = currentVolume();
        hub.updateStatus(b -> {
            b.volume = percent;
            return b;
        });
    }

    /** 读取当前媒体音量百分比。 */
    private int currentVolume() {
        AudioManager audio = getSystemService(AudioManager.class);
        if (audio == null) return config.volume;
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (max <= 0) return 0;
        return Math.round(audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
    }

    /** 播放远程音频，新的播放会停掉上一段。 */
    @Override
    public void playAudio(String url) {
        if (url == null || url.isEmpty()) return;
        Log.i(KioskApp.LOG, "Playing audio URL");
        releasePlayer();
        MediaPlayer next = new MediaPlayer();
        player = next;
        next.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        next.setOnPreparedListener(MediaPlayer::start);
        next.setOnCompletionListener(mp -> {
            if (player == mp) player = null;
            mp.release();
        });
        next.setOnErrorListener((mp, what, extra) -> {
            Log.w(KioskApp.LOG, "Audio playback failed, what=" + what + " extra=" + extra);
            if (player == mp) player = null;
            mp.release();
            return true;
        });
        try {
            next.setDataSource(url);
            next.prepareAsync();
        } catch (Exception e) {
            Log.w(KioskApp.LOG, "Audio source rejected", e);
            if (player == next) player = null;
            next.release();
        }
    }

    /** 释放正在播放的音频。 */
    private void releasePlayer() {
        if (player == null) return;
        player.release();
        player = null;
    }

    /** 密码层是否已关闭。 */
    private boolean pinClosed() {
        return pinGate == null || pinGate.getVisibility() != View.VISIBLE;
    }

    /** 创建密码层，默认隐藏。 */
    private FrameLayout buildPinGate(FrameLayout root) {
        FrameLayout gate = new FrameLayout(this);
        gate.setBackgroundColor(Color.argb(210, 8, 18, 22));
        gate.setVisibility(View.GONE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView title = new TextView(this);
        title.setText(R.string.pin_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        box.addView(title);
        pinInput = new EditText(this);
        pinInput.setSingleLine(true);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setTextColor(Color.WHITE);
        box.addView(pinInput);
        Button confirm = new Button(this);
        confirm.setText(R.string.pin_confirm);
        confirm.setOnClickListener(v -> submitPin());
        box.addView(confirm);
        Button cancel = new Button(this);
        cancel.setText(R.string.close);
        cancel.setOnClickListener(v -> hidePinGate());
        box.addView(cancel);
        gate.addView(box, new FrameLayout.LayoutParams(dp(320), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(gate, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return gate;
    }

    /** 密码正确才打开设置。 */
    private void submitPin() {
        String entered = pinInput.getText() == null ? "" : pinInput.getText().toString().trim();
        if (entered.equals(config.settingsPin)) {
            hidePinGate();
            settings.show();
            syncCamera();
        } else {
            pinInput.setError(getString(R.string.pin_wrong));
            pinInput.setText("");
        }
    }

    /** 关闭密码层并恢复边缘手势。 */
    private void hidePinGate() {
        if (pinGate == null) return;
        pinGate.setVisibility(View.GONE);
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null && pinInput != null) imm.hideSoftInputFromWindow(pinInput.getWindowToken(), 0);
        if (settings == null || !settings.isOpen()) edge.setEdgeEnabled(true);
    }

    /** 显示密码层。 */
    private void showPinGate() {
        pinInput.setText("");
        pinInput.setError(null);
        pinGate.setVisibility(View.VISIBLE);
        pinInput.requestFocus();
    }

    /** dp 转像素。 */
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 保存语言，刷新通知和 MQTT 名称，然后重建界面。 */
    @Override
    public void onLanguageChanged(String language) {
        AppLocales.save(this, language);
        hub.requestReopenSettings();
        startForegroundService(new Intent(this, KioskService.class).setAction(AppLocales.ACTION_REFRESH));
        recreate();
    }

    /** 请求读取 Wi-Fi 名称所需的权限。 */
    @Override
    public void requestWifiPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wifiPermission.launch(new String[] {Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION});
        } else {
            wifiPermission.launch(new String[] {Manifest.permission.ACCESS_FINE_LOCATION});
        }
    }

    /** 是否已经可以读取 Wi-Fi 名称。 */
    @Override
    public boolean wifiGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
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
        syncCamera();
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
        } else if (!wifiGranted()) {
            requestWifiPermission();
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
            Toast.makeText(this, R.string.toast_lock_task, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            systemScreenOpen = true;
            systemScreen.launch(intent);
        } catch (ActivityNotFoundException e) {
            systemScreenOpen = false;
            Log.e(KioskApp.LOG, "System screen not available: " + intent.getAction(), e);
            Toast.makeText(this, R.string.toast_system_screen, Toast.LENGTH_SHORT).show();
        }
    }

    /** 本应用的 package: 地址。 */
    private Uri packageUri() {
        return Uri.parse("package:" + getPackageName());
    }
}
