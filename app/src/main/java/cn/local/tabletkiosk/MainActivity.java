package cn.local.tabletkiosk;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.widget.Toast;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** 全屏显示 HA 页面：空闲刷新、失败重试、观看与待机亮度、摄像头亮屏和左边缘设置。 */
public final class MainActivity extends ComponentActivity
        implements KioskHub.Controller, EdgeSwipeLayout.Listener, SettingsPanel.Host, CameraMotion.Listener {
    private static final long TICK_MS = 1000;
    /** 窗口亮度刚改完的这段时间里，前置摄像头会看见自己造成的明暗变化，不能当成有人。 */
    private static final long BRIGHTNESS_SETTLE_MS = 2500;
    /** 屏保天气沿用 Home Assistant 已有状态，30 分钟才再读一次，避免反复请求。 */
    private static final long WEATHER_TTL_MS = 30 * 60 * 1000L;
    private static final long WAKE_LOCK_MS = 5000;
    private static final long CACHE_PURGE_TIMEOUT_MS = 1500;
    private static final long NOTICE_MS = 8000;
    private static final String LOCK_JS = "try{var s=document.getElementById('kiosk-lock');"
            + "if(!s){s=document.createElement('style');s.id='kiosk-lock';"
            + "(document.head||document.documentElement).appendChild(s);}"
            + "s.textContent='*{user-select:none!important;-webkit-user-select:none!important;"
            + "-webkit-touch-callout:none!important;-webkit-user-drag:none!important}"
            + "input,textarea,select,[contenteditable]{user-select:text!important;-webkit-user-select:text!important;"
            + "-webkit-touch-callout:default!important;-webkit-user-drag:auto!important}';}catch(e){}";
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
            if (!hub.isController(MainActivity.this)) return;
            onTick();
            handler.postDelayed(this, TICK_MS);
        }
    };
    private final Runnable retry = this::retryLoad;
    private String offlineUrl;
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
    private final ActivityResultLauncher<String> micPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                Log.i(KioskApp.LOG, "Microphone permission granted=" + granted);
                startKioskService();
                AssistRuntime.start(this).apply();
                refreshPanel();
            });
    private final ActivityResultLauncher<String[]> wifiPermission =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), granted -> {
                Log.i(KioskApp.LOG, "Wi-Fi permission result received");
                publishDeviceInfo();
                refreshPanel();
                maybePromptBattery();
            });
    private final ActivityResultLauncher<String> exportFile =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), this::writeSettingsFile);
    private final ActivityResultLauncher<String[]> importFile =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::readSettingsFile);
    private final ActivityResultLauncher<Intent> systemScreen =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                systemScreenOpen = false;
                refreshPanel();
            });
    private final ActivityResultLauncher<Intent> webFilePicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                filePickerOpen = false;
                Uri[] selected = null;
                try {
                    // 部分 WebView 的 parseResult 只读取 data URI，多选仅返回 ClipData，必须显式处理。
                    List<Uri> parsed = new ArrayList<>();
                    Intent data = result.getData();
                    if (result.getResultCode() == RESULT_OK && data != null) {
                        ClipData clips = data.getClipData();
                        if (clips != null) {
                            for (int i = 0; i < clips.getItemCount(); i++) parsed.add(clips.getItemAt(i).getUri());
                        } else if (data.getData() != null) parsed.add(data.getData());
                    }
                    List<Uri> accepted = new ArrayList<>();
                    for (Uri uri : parsed) {
                        // 系统选择器只返回临时授权的内容地址，不接受网络地址或任意本地路径。
                        if (uri != null && "content".equalsIgnoreCase(uri.getScheme())) accepted.add(uri);
                        else Log.w(KioskApp.LOG, "Rejected unsupported web upload URI scheme");
                    }
                    if (!accepted.isEmpty()) selected = accepted.toArray(new Uri[0]);
                } catch (RuntimeException e) {
                    Log.e(KioskApp.LOG, "Web upload result parsing failed", e);
                    Toast.makeText(this, R.string.toast_web_file_failed, Toast.LENGTH_LONG).show();
                }
                finishWebFileSelection(selected);
                onUserTouch();
                syncLockTask();
            });
    private ValueCallback<Uri[]> webFileCallback;
    private boolean filePickerOpen;

    private KioskHub hub;
    private KioskConfig config;
    private PowerManager power;
    private EdgeSwipeLayout edge;
    private SettingsPanel settings;
    private TopPullRefreshLayout refresh;
    private WebView web;
    private TextView notice;
    private View offlinePage;
    private TextView offlineTitle;
    private TextView offlineDetail;
    private FailKind failKind = FailKind.SITE;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean networkWasDown;

    /** 等待页要区分的失败原因。 */
    private enum FailKind {
        NETWORK,
        SITE,
        SSL
    }
    private TextToSpeech tts;
    private MediaPlayer player;
    private FrameLayout pinGate;
    private EditText pinInput;
    private int infoTick;
    private CpuMonitor cpuMonitor;
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
    /** 已经开始加载过首页。首次设置确认后避免再加载一次。 */
    private boolean homeLoaded;
    private boolean assistHold;
    private MovableAssistButton assistButton;
    private AssistPanel assistPanel;
    private ScreenSaver screenSaver;
    private String weatherLine = "";
    private long weatherFetchedAt;
    private boolean weatherFetching;
    private int weatherGeneration;
    /** 已经写到窗口上的亮度百分比。-1 表示还没写过。 */
    private int appliedBrightnessPercent = -1;
    /** 这次改亮度之后，在这个单调时钟之前忽略“屏幕仍亮着”时的摄像头回调。 */
    private long brightnessSettleUntil;

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
        buildOffline(root);
        pinGate = buildPinGate(root);
        buildAssist(root);
        screenSaver = new ScreenSaver(this, this::onScreenSaverDismiss);
        root.addView(screenSaver, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
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
        long now = SystemClock.elapsedRealtime();
        presence = new PresenceTracker(config.dimDelaySeconds, config.presenceHoldSeconds, now);
        idle = new IdleReload(config.idleReloadHours, now);
        camera = new CameraMotion(this, this);
        // HOME 和普通任务可能各建一个实例，必须先接管再启动定时和状态发布。
        hub.setController(this);
        publishDeviceInfo();
        handler.postDelayed(this::publishDeviceInfo, 5000);
        createWebView();
        if (hub.store().needsSetup()) {
            // 还没有确认过地址，先打开设置，不连接占位首页
            settings.post(() -> {
                edge.setEdgeEnabled(false);
                settings.beginSetup();
                syncCamera();
                syncPullRefresh();
            });
        } else {
            // 安装新版本不会清空上次的网页缓存，启动时丢掉旧前端再加载
            purgeOnNextFinish = true;
            loadFresh(config.homeUrl);
            firstRunPrompts();
            checkForUpdate(false);
        }

        hub.addConfigListener(configListener);
        hub.addStatusListener(statusListener);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            /** 返回键：先关设置，再网页后退，不退出应用。 */
            @Override
            public void handleOnBackPressed() {
                if (pinGate != null && pinGate.getVisibility() == View.VISIBLE) hidePinGate();
                else if (settings.isSetup()) return;
                else if (settings.isOpen()) settings.hide();
                else if (web.canGoBack()) web.goBack();
            }
        });
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        ContextCompat.registerReceiver(this, screenReceiver, screen, ContextCompat.RECEIVER_NOT_EXPORTED);

        startKioskService();
        AssistRuntime.start(this).setListener(new AssistRuntime.Listener() {
            @Override
            public void onAssist(String state, String detail) {
                showAssist(state, detail);
            }

            /** 把本轮实际麦克风音量交给声波面板。 */
            @Override
            public void onAssistLevel(float level) {
                if (assistPanel != null) assistPanel.setLevel(level);
            }

            @Override
            public void onAssistWake() {
                wake();
            }

            @Override
            public void onAssistPlayback(String url, Runnable finished) {
                playAuthorized(url, finished);
            }

            /** 第二次点击语音按钮时停止回答，保留正常的常驻唤醒。 */
            @Override
            public void onAssistCancel() {
                releasePlayer();
            }

            @Override
            public void onAssistHold(boolean hold) {
                assistHold = hold;
                if (hold) wake();
                applyBrightness();
                syncScreenSaver();
            }
        });
        DeviceOwnerPolicy.applyPolicies(this, config.lockTaskEnabled);
        refreshOwnerStatus();
        applyBrightness();
        applyVolume();
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
        watchNetwork();
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
        unwatchNetwork();
        super.onStop();
    }

    /** 新实例接管后停止旧实例后台任务，并结束旧任务中的界面。 */
    @Override
    public void onControllerReplaced() {
        Log.i(KioskApp.LOG, "Dashboard controller replaced: retiring activity="
                + Integer.toHexString(System.identityHashCode(this)) + " task=" + getTaskId());
        handler.removeCallbacksAndMessages(null);
        hub.removeConfigListener(configListener);
        hub.removeStatusListener(statusListener);
        camera.stop();
        unwatchNetwork();
        finish();
    }

    /** 释放网页、摄像头和监听。 */
    @Override
    protected void onDestroy() {
        // 旧界面可能在新界面建立后才销毁，不能清掉新界面的语音监听和控制器。
        if (hub.clearController(this)) AssistRuntime.start(this).setListener(null);
        hub.removeConfigListener(configListener);
        hub.removeStatusListener(statusListener);
        try {
            unregisterReceiver(screenReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(KioskApp.LOG, "Screen receiver was not registered", e);
        }
        camera.release();
        finishWebFileSelection(null);
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
        // 重建网页时取消旧页面的选择请求，迟到的结果不能交给新页面。
        finishWebFileSelection(null);
        if (refresh != null) {
            edge.removeView(refresh);
            refresh = null;
        }
        if (web != null) {
            ViewParent parent = web.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(web);
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
        // 允许读取用户通过系统选择器授权的 content URI，file:// 访问仍关闭。
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        // 让页面的 viewport 按设备宽度排版，固定画布才能缩进可见区域
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        // 不使用 HTTP 缓存，避免 HA 前端更新后仍显示旧界面
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        CookieManager.getInstance().setAcceptCookie(true);
        web.addJavascriptInterface(new CacheBridge(), "KioskCache");
        web.setLongClickable(true);
        web.setHapticFeedbackEnabled(false);
        // 输入框保留系统的文本操作，其余位置仍吃掉长按菜单
        web.setOnLongClickListener(v -> web.getHitTestResult() == null
                || web.getHitTestResult().getType() != android.webkit.WebView.HitTestResult.EDIT_TEXT_TYPE);
        web.setWebViewClient(new KioskWebClient());
        web.setWebChromeClient(new WebChromeClient() {
            /** 接管网页文件上传，选取或取消后恰好回调一次。 */
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (filePickerOpen) {
                    Log.w(KioskApp.LOG, "Ignored duplicate web file chooser request");
                    callback.onReceiveValue(null);
                    return true;
                }
                finishWebFileSelection(null);
                webFileCallback = callback;
                if (DeviceOwnerPolicy.inLockTask(MainActivity.this)) {
                    Log.w(KioskApp.LOG, "Web file chooser blocked by lock task mode");
                    Toast.makeText(MainActivity.this, R.string.toast_lock_task, Toast.LENGTH_LONG).show();
                    finishWebFileSelection(null);
                    return true;
                }
                if (params.getMode() != FileChooserParams.MODE_OPEN
                        && params.getMode() != FileChooserParams.MODE_OPEN_MULTIPLE) {
                    Log.w(KioskApp.LOG, "Unsupported web file chooser mode");
                    Toast.makeText(MainActivity.this, R.string.toast_web_file_failed, Toast.LENGTH_LONG).show();
                    finishWebFileSelection(null);
                    return true;
                }
                try {
                    String[] types = WebUploadTypes.normalize(params.getAcceptTypes());
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType(WebUploadTypes.primary(types));
                    if (types.length > 0) intent.putExtra(Intent.EXTRA_MIME_TYPES, types);
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    filePickerOpen = true;
                    onUserTouch();
                    webFilePicker.launch(intent);
                    Log.i(KioskApp.LOG, "Web file chooser launched");
                } catch (RuntimeException e) {
                    filePickerOpen = false;
                    finishWebFileSelection(null);
                    Log.e(KioskApp.LOG, "Web file chooser launch failed", e);
                    Toast.makeText(MainActivity.this, R.string.toast_web_file_failed, Toast.LENGTH_LONG).show();
                }
                return true;
            }
        });
        refresh = new TopPullRefreshLayout(this);
        refresh.setListener(this::pullToRefresh);
        refresh.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        edge.addView(refresh, 0, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        edge.setTapTarget(web);
        syncPullRefresh();
    }

    /** 完成或取消当前网页文件请求，不记录用户选择的文件路径。 */
    private void finishWebFileSelection(Uri[] selected) {
        ValueCallback<Uri[]> callback = webFileCallback;
        webFileCallback = null;
        if (callback != null) {
            try {
                callback.onReceiveValue(selected);
                Log.i(KioskApp.LOG, selected == null ? "Web file selection cancelled" : "Web file selection delivered count=" + selected.length);
            } catch (RuntimeException e) {
                Log.e(KioskApp.LOG, "Web file selection callback failed", e);
            }
        }
    }

    /** 从画面顶部下拉时清缓存并重新加载当前页，不改已保存的首页。 */
    private void pullToRefresh() {
        Log.i(KioskApp.LOG, "Pull to refresh");
        reload();
    }

    /** 设置或密码层打开时不响应下拉。 */
    private void syncPullRefresh() {
        if (refresh == null) return;
        boolean blocked = (settings != null && settings.isOpen()) || !pinClosed();
        refresh.setEnabled(!blocked);
        if (blocked) refresh.setRefreshing(false);
    }

    /** 页面加载结束或失败后收起下拉指示。 */
    private void stopPullRefresh() {
        if (refresh != null) refresh.setRefreshing(false);
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
            finishWebFileSelection(null);
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
                hideOffline();
                watchdog.onPageFinished(SystemClock.elapsedRealtime());
            }
            view.evaluateJavascript(LOCK_JS, null);
            stopPullRefresh();
            if (purgeOnNextFinish) {
                purgeOnNextFinish = false;
                purgeThenLoad(current.isEmpty() ? config.homeUrl : current);
            }
        }

        /** 主页面网络错误时安排重试。 */
        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                onLoadFailure("Main frame load failed: code=" + error.getErrorCode() + " " + error.getDescription(),
                        request.getUrl().toString(), FailKind.SITE);
            }
        }

        /** 主页面服务器 5xx 错误时安排重试。 */
        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame() && response.getStatusCode() >= 500) {
                onLoadFailure("Main frame HTTP error: " + response.getStatusCode(), request.getUrl().toString(),
                        FailKind.SITE);
            }
        }

        /** 证书错误一律拒绝，不忽略校验。 */
        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            Log.e(KioskApp.LOG, "SSL error rejected: primary=" + error.getPrimaryError());
            handler.cancel();
            onLoadFailure("SSL error rejected: primary=" + error.getPrimaryError(), error.getUrl(), FailKind.SSL);
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

    /** 主页面失败时盖上本地等待页，并继续重试。证书错误不自动重试。 */
    private void onLoadFailure(String reason, String url, FailKind kind) {
        mainFrameError = true;
        stopPullRefresh();
        Log.w(KioskApp.LOG, reason);
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) offlineUrl = url;
        FailKind shown = kind == FailKind.SSL ? FailKind.SSL
                : (NetUtil.hasNetwork(this) ? FailKind.SITE : FailKind.NETWORK);
        showOffline(offlineUrl == null || offlineUrl.isEmpty() ? config.homeUrl : offlineUrl, shown);
        if (retryPending || shown == FailKind.SSL) return;
        int seconds = config.retrySeconds > 0 ? config.retrySeconds : 30;
        retryPending = true;
        handler.postDelayed(retry, seconds * 1000L);
    }

    /** 执行一次重试：不走缓存重新打开失败的页面，没有网址时回首页。 */
    private void retryLoad() {
        retryPending = false;
        String url = offlineUrl;
        if (url == null || url.isEmpty()) url = web.getUrl();
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
        // 选文件时不让空闲刷新、看门狗或夜间息屏中断上传请求。
        if (filePickerOpen) {
            presence.onActivity(now);
            idle.onInteraction(now);
            return;
        }
        Calendar calendar = Calendar.getInstance();
        // 设置和密码页不在边缘手势下面，触摸到不了 onUserTouch。人就在看面板，不能让观看计时在这里走完。
        boolean panelOpen = settings != null && (settings.isOpen() || settings.isSetup());
        if (panelOpen || !pinClosed()) presence.onActivity(now);
        presence.tick(now);
        applyBrightness();
        if (!settings.isOpen() && pinClosed() && dailyReload.due(config.dailyReloadEnabled, config.dailyReloadHour,
                config.dailyReloadMinute, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE),
                calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR))) {
            Log.i(KioskApp.LOG, "Daily reload triggered at " + config.dailyReloadHour + ":" + config.dailyReloadMinute);
            reload();
        }
        applySleep(now, calendar.get(Calendar.HOUR_OF_DAY));
        syncScreenSaver();
        checkWatchdog(now);
        if (++infoTick >= 60) {
            infoTick = 0;
            publishDeviceInfo();
        }
        if (!assistHold && !settings.isOpen() && idle.shouldReload(now)) {
            Log.i(KioskApp.LOG, "Idle reload triggered after " + config.idleReloadHours + " h without touch");
            reload();
        }
    }

    /** 按观看、待机或屏保设置本窗口亮度，不改系统全局亮度。屏保显示时只用屏保亮度。 */
    private void applyBrightness() {
        if (!hub.isController(this)) return;
        // 所有调用入口都保护本地操作，不依赖下一次定时器才能恢复观看亮度。
        boolean localUi = settings != null && (settings.isOpen() || settings.isSetup() || !pinClosed());
        if (localUi) presence.onActivity(SystemClock.elapsedRealtime());
        boolean watching = assistHold || localUi || presence.watching();
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int percent = BrightnessSchedule.currentBrightness(config, hour, watching,
                shouldShowScreenSaver(), localUi);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        float value = percent / 100f;
        // 系统读回的浮点经常和写入值差一位。用绝对相等会每秒 setAttributes，亮度来回跳。
        if (!sameWindowBrightness(lp.screenBrightness, value)) {
            lp.screenBrightness = value;
            getWindow().setAttributes(lp);
        }
        if (percent != appliedBrightnessPercent) {
            Log.i(KioskApp.LOG, "Window brightness changed: activity="
                    + Integer.toHexString(System.identityHashCode(this)) + " task=" + getTaskId()
                    + " previous=" + appliedBrightnessPercent
                    + " target=" + percent + " localUi=" + localUi + " watching=" + watching
                    + " screensaver=" + shouldShowScreenSaver() + " assist=" + assistHold);
            appliedBrightnessPercent = percent;
            // 只在档位真正变化时忽略随后的摄像头帧，避免自己把屏幕调暗又被当成有人调亮
            brightnessSettleUntil = SystemClock.elapsedRealtime() + BRIGHTNESS_SETTLE_MS;
        }
        boolean someone = presence.presence();
        hub.updateStatus(b -> {
            b.brightness = percent;
            b.watching = watching;
            b.presence = someone;
            return b;
        });
    }

    /** 窗口亮度是否已经是目标值。NaN 和未设置（-1）都算还没写上。允许约 2/255 的读回误差，避免 0% 被系统抬到最低亮度后又写回去。 */
    private static boolean sameWindowBrightness(float current, float target) {
        if (Float.isNaN(current) || current < 0f) return false;
        return Math.abs(current - target) <= (2f / 255f);
    }

    /** 读取系统屏幕是否点亮，用于“亮屏”状态。 */
    private void updateInteractive() {
        if (!hub.isController(this)) return;
        boolean on = power != null && power.isInteractive();
        hub.updateStatus(b -> {
            b.interactive = on;
            return b;
        });
    }

    /** 更新设备所有者和锁定模式状态。 */
    private void refreshOwnerStatus() {
        if (!hub.isController(this)) return;
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
        if (!hub.isController(this)) return;
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
        syncAssistButton();
        syncCamera();
        syncLockTask();
        syncScreenSaver();
    }

    /** 按配置和权限打开或关闭摄像头检测。 */
    private void syncCamera() {
        if (!hub.isController(this)) return;
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
        if (systemScreenOpen || filePickerOpen) return;
        DeviceOwnerPolicy.applyPolicies(this, config.lockTaskEnabled);
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
        syncScreenSaver();
    }

    /** 点屏保只恢复观看，不把这次点击交给网页。 */
    private void onScreenSaverDismiss() {
        onUserTouch();
    }

    /**
     * 是否该盖上屏保。有人、设置、密码、语音期间不盖。
     * 关掉「启用屏保」后也不盖，待机变暗和夜间息屏仍走原来的判断。
     */
    private boolean shouldShowScreenSaver() {
        return started && config != null && config.screensaverEnabled && presence != null && !presence.watching()
                && !assistHold && settings != null && !settings.isOpen() && !settings.isSetup() && pinClosed();
    }

    /** 无人且启用屏保时盖上覆盖层；有人、设置或语音期间收起。点按仍只关闭屏保。 */
    private void syncScreenSaver() {
        if (screenSaver == null || presence == null || config == null) return;
        boolean show = shouldShowScreenSaver();
        boolean drawLunar = show && config.screensaverLunar;
        boolean drawWeather = show && config.screensaverWeather;
        boolean wasShown = screenSaver.getVisibility() == View.VISIBLE;
        String lunar = drawLunar ? LunarDate.format(LocalDateTime.now().toLocalDate()) : "";
        screenSaver.setShown(show, LocalDateTime.now(), lunar, drawWeather ? weatherLine : "", drawLunar, drawWeather);
        // 图标有独立的悬浮层级，屏保显示时必须显式隐藏，退出时再恢复。
        syncAssistButton();
        if (show != wasShown) Log.i(KioskApp.LOG, show ? "Screensaver shown" : "Screensaver hidden");
        // 天气关掉时不请求 Home Assistant
        if (drawWeather) refreshWeather();
    }

    /** 用已保存的首页和长期访问令牌读取 Home Assistant 天气。没有可显示文字时清空，屏保不画这一行。 */
    private void refreshWeather() {
        long now = SystemClock.elapsedRealtime();
        if (weatherFetching || now - weatherFetchedAt < WEATHER_TTL_MS) return;
        weatherFetching = true;
        int generation = ++weatherGeneration;
        String home = config.homeUrl;
        String token = hub.store().haToken();
        boolean chinese = AppLocales.ZH.equals(AppLocales.effective(this));
        new Thread(() -> {
            String line = HaWeather.display(HaWeather.fetch(home, token), chinese);
            handler.post(() -> {
                if (generation != weatherGeneration) return;
                weatherFetching = false;
                weatherFetchedAt = SystemClock.elapsedRealtime();
                weatherLine = line == null ? "" : line;
                if (screenSaver != null) screenSaver.setWeather(weatherLine);
            });
        }, "kiosk-weather").start();
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
        applyBrightness();
        syncScreenSaver();
        syncPullRefresh();
    }

    /** 摄像头检测到画面变化：标记有人；屏幕已熄时点亮。刚改完亮度的帧不当成有人。 */
    @Override
    public void onMotion() {
        if (!hub.isController(this)) return;
        boolean dark = power != null && !power.isInteractive();
        if (!dark && SystemClock.elapsedRealtime() < brightnessSettleUntil) {
            Log.i(KioskApp.LOG, "Ignoring camera motion while brightness is settling");
            return;
        }
        presence.onMotion(SystemClock.elapsedRealtime());
        if (dark) wake();
        else applyBrightness();
        syncScreenSaver();
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
        homeLoaded = true;
        offlineUrl = url;
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
        homeLoaded = true;
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

    /** 网页打不开时盖住画面的本地等待页，设置面板仍在它上面。 */
    private void buildOffline(FrameLayout root) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.rgb(12, 28, 36));
        int pad = Math.round(28 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        offlineTitle = new TextView(this);
        offlineTitle.setText(R.string.offline_title);
        offlineTitle.setTextColor(Color.WHITE);
        offlineTitle.setTextSize(28);
        offlineTitle.setGravity(Gravity.CENTER);
        box.addView(offlineTitle);
        offlineDetail = new TextView(this);
        offlineDetail.setText(R.string.offline_body);
        offlineDetail.setTextColor(Color.rgb(186, 214, 214));
        offlineDetail.setTextSize(16);
        offlineDetail.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = pad / 2;
        box.addView(offlineDetail, detailLp);
        box.setVisibility(View.GONE);
        root.addView(box, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        offlinePage = box;
    }

    /** 显示等待页，并按没网、站点没开或证书错误写说明。 */
    private void showOffline(String url, FailKind kind) {
        if (offlinePage == null || offlineTitle == null) return;
        failKind = kind;
        int title = kind == FailKind.NETWORK ? R.string.offline_title_network
                : kind == FailKind.SSL ? R.string.offline_title_ssl : R.string.offline_title_site;
        int body = kind == FailKind.NETWORK ? R.string.offline_body_network
                : kind == FailKind.SSL ? R.string.offline_body_ssl : R.string.offline_body_site;
        offlineTitle.setText(title);
        offlineDetail.setText(getString(body, url == null ? "" : url));
        offlinePage.setVisibility(View.VISIBLE);
        offlinePage.bringToFront();
        if (settings != null) settings.bringToFront();
        if (pinGate != null) pinGate.bringToFront();
        if (notice != null) notice.bringToFront();
    }

    /** 监听默认网络。断网时改等待页文案，网络恢复后立刻重试。 */
    private void watchNetwork() {
        if (networkCallback != null) return;
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;
        networkWasDown = !NetUtil.hasNetwork(this);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            /** 默认网络出现。只有等待页开着时才立刻重试。 */
            @Override
            public void onAvailable(Network network) {
                handler.post(() -> {
                    boolean recover = networkWasDown;
                    networkWasDown = false;
                    if (recover) retryAfterNetwork();
                });
            }

            /** 默认网络消失时，把等待页改成没网。 */
            @Override
            public void onLost(Network network) {
                handler.post(() -> {
                    networkWasDown = true;
                    if (offlinePage != null && offlinePage.getVisibility() == View.VISIBLE
                            && failKind != FailKind.SSL) {
                        showOffline(offlineUrl, FailKind.NETWORK);
                    }
                });
            }
        };
        try {
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            Log.w(KioskApp.LOG, "Network callback was not registered", e);
            networkCallback = null;
        }
    }

    /** 离开前台时取消网络监听。 */
    private void unwatchNetwork() {
        if (networkCallback == null) return;
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException e) {
                Log.w(KioskApp.LOG, "Network callback was not registered", e);
            }
        }
        networkCallback = null;
    }

    /** 网络恢复后马上重开失败页，不等下一次定时重试。 */
    private void retryAfterNetwork() {
        if (offlinePage == null || offlinePage.getVisibility() != View.VISIBLE) return;
        if (failKind == FailKind.SSL) return;
        Log.i(KioskApp.LOG, "Network restored, retrying now");
        cancelRetry();
        retryLoad();
    }

    /** 对照 GitHub 最新正式版。自动检查大约 20 小时一次，失败只写日志。 */
    private void checkForUpdate(boolean manual) {
        long now = System.currentTimeMillis();
        if (!manual && now - hub.store().updateCheckedAt() < 20L * 60 * 60 * 1000L) return;
        if (!manual) hub.store().markUpdateChecked(now);
        new Thread(() -> {
            AppUpdate.Release release = AppUpdate.latest();
            handler.post(() -> presentUpdate(release, manual));
        }, "app-update").start();
    }

    /** 有更新时弹出下载提示。手动检查会说明已是最新或连不上。 */
    private void presentUpdate(AppUpdate.Release release, boolean manual) {
        if (isFinishing()) return;
        if (release == null || release.version.isEmpty()) {
            if (manual) Toast.makeText(this, R.string.update_unreachable, Toast.LENGTH_LONG).show();
            return;
        }
        if (!AppUpdate.newer(BuildConfig.VERSION_NAME, release.version)) {
            if (manual) Toast.makeText(this, R.string.update_current, Toast.LENGTH_LONG).show();
            return;
        }
        String target = release.apkUrl.isEmpty() ? release.pageUrl : release.apkUrl;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_title, release.version))
                .setMessage(R.string.update_body)
                .setPositiveButton(R.string.update_download, (dialog, which) -> {
                    if (!target.isEmpty()) {
                        openSystemScreen(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
                    }
                })
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    /** 主页面已经打开时收起等待页。 */
    private void hideOffline() {
        offlineUrl = null;
        if (offlinePage != null) offlinePage.setVisibility(View.GONE);
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
        if (!hub.isController(this)) return;
        String ip = NetUtil.localIpv4(this);
        String version = BuildConfig.VERSION_NAME;
        String model = Build.MANUFACTURER + " " + Build.MODEL;
        String android = Build.VERSION.RELEASE;
        if (cpuMonitor == null) cpuMonitor = CpuMonitor.create(this);
        int cpu = cpuMonitor.sample();
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
        if (asleep && interactive && !assistHold && presence.quiet(now) && settings != null && !settings.isOpen() && pinClosed()
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

    /** 默认右下角的麦克风，长按可拖动；重新打开应用时恢复保存的位置。 */
    private void buildAssist(FrameLayout root) {
        assistPanel = new AssistPanel(this, () -> AssistRuntime.start(this).cancel());
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(dp(520), FrameLayout.LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        panelLp.bottomMargin = dp(24);
        root.addView(assistPanel, panelLp);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> layoutAssistPanel());
        assistButton = new MovableAssistButton(this, hub.store(), this::onUserTouch, this::layoutAssistPanel);
        assistButton.setImageDrawable(new AssistIconDrawable(getResources()));
        assistButton.setScaleType(ImageView.ScaleType.FIT_CENTER);
        assistButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        assistButton.setBackgroundColor(Color.TRANSPARENT);
        assistButton.setElevation(dp(16));
        assistButton.setContentDescription(getString(R.string.assist_idle));
        assistButton.setOnClickListener(v -> AssistRuntime.start(this).tap());
        FrameLayout.LayoutParams buttonLp = new FrameLayout.LayoutParams(dp(72), dp(72));
        buttonLp.gravity = Gravity.END | Gravity.BOTTOM;
        buttonLp.setMarginEnd(dp(20));
        buttonLp.bottomMargin = dp(20);
        root.addView(assistButton, buttonLp);
        syncAssistButton();
    }

    /** 底部面板限制最大宽度，横竖屏和小窗口下均留出边距。 */
    private void layoutAssistPanel() {
        if (assistPanel == null || !(assistPanel.getParent() instanceof View)) return;
        View parent = (View) assistPanel.getParent();
        int width = Math.min(dp(520), Math.max(0, parent.getWidth() - dp(40)));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) assistPanel.getLayoutParams();
        if (width > 0 && lp.width != width) {
            lp.width = width;
            assistPanel.setLayoutParams(lp);
        }
    }

    /** 语音助手开启且没有显示屏保时才显示麦克风，不改变常驻唤醒开关。 */
    private void syncAssistButton() {
        if (assistButton == null) return;
        boolean enabled = config != null && config.assistEnabled;
        boolean screensaverShown = screenSaver != null && screenSaver.getVisibility() == View.VISIBLE;
        assistButton.setVisibility(enabled && !screensaverShown ? View.VISIBLE : View.GONE);
        if (!enabled && assistPanel != null) assistPanel.showState(AssistRuntime.IDLE, "");
    }

    /** 图标保持面板配色，通过面板内容和状态描述反馈语音进度。 */
    private void showAssist(String state, String detail) {
        if (assistButton == null) return;
        int label = R.string.assist_idle;
        if (AssistRuntime.LISTENING.equals(state)) label = R.string.assist_listening;
        else if (AssistRuntime.THINKING.equals(state)) label = R.string.assist_thinking;
        else if (AssistRuntime.SPEAKING.equals(state)) label = R.string.assist_speaking;
        assistButton.setContentDescription(getString(label));
        assistButton.setStateDescription(getString(label));
        if (assistPanel != null) assistPanel.showState(state, detail);
        if ("token".equals(detail)) {
            Toast.makeText(this, R.string.assist_need_token, Toast.LENGTH_SHORT).show();
        } else if ("unauthorized".equals(detail) || "unreachable".equals(detail)) {
            Toast.makeText(this, "unauthorized".equals(detail)
                    ? R.string.assist_unauthorized : R.string.assist_unreachable, Toast.LENGTH_SHORT).show();
        }
    }

    /** 播放带访问令牌的回答音频。播完执行 finished。 */
    private void playAuthorized(String url, Runnable finished) {
        if (url == null || url.isEmpty()) {
            if (finished != null) finished.run();
            return;
        }
        Log.i(KioskApp.LOG, "Playing assist audio");
        releasePlayer();
        MediaPlayer next = new MediaPlayer();
        player = next;
        next.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build());
        next.setOnCompletionListener(mp -> {
            if (player == mp) player = null;
            mp.release();
            if (finished != null) finished.run();
        });
        next.setOnErrorListener((mp, what, extra) -> {
            Log.w(KioskApp.LOG, "Assist audio failed, what=" + what + " extra=" + extra);
            if (player == mp) player = null;
            mp.release();
            if (finished != null) finished.run();
            return true;
        });
        try {
            Map<String, String> headers = new HashMap<>();
            String token = hub.store().haToken();
            if (!token.isEmpty()) headers.put("Authorization", "Bearer " + token);
            next.setDataSource(this, Uri.parse(url), headers);
            next.prepareAsync();
            next.setOnPreparedListener(mp -> {
                // 取消后到达的准备完成事件不能重新启动已释放的回答。
                if (player == mp) mp.start();
            });
        } catch (Exception e) {
            Log.w(KioskApp.LOG, "Assist audio source rejected", e);
            if (player == next) player = null;
            next.release();
            if (finished != null) finished.run();
        }
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
            applyBrightness();
            syncScreenSaver();
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
        syncPullRefresh();
    }

    /** 显示密码层。 */
    private void showPinGate() {
        pinInput.setText("");
        pinInput.setError(null);
        pinGate.setVisibility(View.VISIBLE);
        pinInput.requestFocus();
        syncPullRefresh();
    }

    /** dp 转像素。 */
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 只记下语言并刷新已经打开的设置面板，不重建界面、不重载网页、不刷新通知或 MQTT。 */
    @Override
    public void onLanguageChanged(String language) {
        AppLocales.save(this, language);
        settings.applyLocale(language);
    }

    /** 让用户选一个位置保存设置文件。 */
    @Override
    public void exportSettings() {
        exportFile.launch("ha-kiosk-settings.json");
    }

    /** 让用户选一份以前导出的设置文件。 */
    @Override
    public void importSettings() {
        importFile.launch(new String[] {"application/json", "text/*", "*/*"});
    }

    /** 把当前设置写入用户选定的文件。内容含密码，不写日志。 */
    private void writeSettingsFile(Uri uri) {
        if (uri == null) return;
        String json = SettingsFile.toJson(hub.config(), AppLocales.saved(this), hub.store().haToken());
        new Thread(() -> {
            boolean ok = false;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out != null) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                    ok = true;
                }
            } catch (Exception e) {
                Log.w(KioskApp.LOG, "Settings export failed", e);
            }
            boolean saved = ok;
            handler.post(() -> Toast.makeText(this, saved ? R.string.settings_exported : R.string.settings_import_failed,
                    Toast.LENGTH_SHORT).show());
        }, "settings-export").start();
    }

    /** 读回设置文件并立即生效。设备编号不改。 */
    private void readSettingsFile(Uri uri) {
        if (uri == null) return;
        new Thread(() -> {
            SettingsFile.Document document = null;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input != null) document = SettingsFile.fromJson(readUtf8(input));
            } catch (Exception e) {
                Log.w(KioskApp.LOG, "Settings import failed", e);
            }
            SettingsFile.Document loaded = document;
            handler.post(() -> applyImportedSettings(loaded));
        }, "settings-import").start();
    }

    /** 按 UTF-8 读完输入流。 */
    private static String readUtf8(InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) out.write(buffer, 0, count);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 在主线程应用导入结果，并刷新已经打开的设置。 */
    private void applyImportedSettings(SettingsFile.Document document) {
        if (document == null || document.error != null || document.config == null) {
            Log.w(KioskApp.LOG, "Settings import rejected");
            Toast.makeText(this, R.string.settings_import_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        hub.store().saveHaToken(document.haToken);
        hub.updateConfig(document.config);
        config = hub.config();
        hub.store().markSetupDone();
        settings.exitSetup();
        if (!homeLoaded) loadFresh(config.homeUrl);
        if (!document.language.isEmpty()) AppLocales.save(this, document.language);
        settings.reloadImported(document.language.isEmpty() ? AppLocales.effective(this) : document.language);
        Toast.makeText(this, R.string.settings_imported, Toast.LENGTH_SHORT).show();
    }

    /** 请求读取 Wi-Fi 名称所需的权限。 */
    @Override
    public void requestWifiPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Wi-Fi 名称仍需要精确定位，Android 12 起须同时申请近似定位。
            wifiPermission.launch(new String[] {Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION});
        } else {
            wifiPermission.launch(new String[] {Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION});
        }
    }

    /** 是否已经可以读取 Wi-Fi 名称。 */
    @Override
    public boolean wifiGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** 请求麦克风权限。 */
    @Override
    public void requestMicPermission() {
        micPermission.launch(Manifest.permission.RECORD_AUDIO);
    }

    /** 是否已经允许使用麦克风。 */
    @Override
    public boolean micGranted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
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

    /** 关闭锁定和固定桌面，尝试取消设备所有者，并打开其他桌面。 */
    @Override
    public void releaseKiosk() {
        KioskConfig.Builder builder = hub.config().toBuilder();
        builder.lockTaskEnabled = false;
        KioskConfig next = builder.build();
        if (!next.equals(hub.config())) hub.updateConfig(next);
        else syncLockTask();
        boolean cleared = DeviceOwnerPolicy.clearDeviceOwner(this);
        if (settings != null && settings.isOpen()) settings.hide();
        boolean opened = DeviceOwnerPolicy.openOtherHome(this);
        int message = cleared ? R.string.release_owner_cleared
                : opened ? R.string.release_owner_kept : R.string.release_no_launcher;
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /** 设置里手动检查更新。 */
    @Override
    public void checkForUpdate() {
        checkForUpdate(true);
    }

    /** 设置面板关闭后恢复边缘手势和全屏。 */
    @Override
    public void onPanelClosed() {
        edge.setEdgeEnabled(true);
        syncPullRefresh();
        hideSystemBars();
        syncCamera();
        // 设置里改过屏保开关或亮度后，关上面板马上按新配置盖屏或恢复亮度
        applyBrightness();
        syncScreenSaver();
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

    /** 首次设置确认后加载首页，并再提示权限。 */
    @Override
    public void onSetupFinished() {
        if (!homeLoaded) loadFresh(config.homeUrl);
        firstRunPrompts();
        checkForUpdate(false);
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
