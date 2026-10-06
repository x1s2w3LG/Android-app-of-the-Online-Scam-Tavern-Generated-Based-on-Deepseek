package com.liarsbar.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import java.util.Locale;

/**
 * 游戏主界面：内嵌 WebView 打开 {@code http://127.0.0.1:<port>/}。
 *
 * <p>职责：
 * <ol>
 *   <li>启动 {@link ServerService}（前台服务）把 Java 服务端跑起来</li>
 *   <li>轮询 / 监听服务端端口，就绪后加载本机页面（最多等 10 秒）</li>
 *   <li>屏幕常亮（主机不能被锁屏掐断）</li>
 *   <li>WebView 只允许停留在本机地址；返回键先交给网页历史</li>
 * </ol>
 *
 * <p>不依赖任何第三方库（没有 androidx），只用系统 API。
 */
public class MainActivity extends Activity implements ServerService.ServerStateListener {

    private static final String TAG = "MainActivity";

    /** 等待服务端就绪的最长时间（毫秒）。 */
    private static final long PORT_WAIT_TIMEOUT_MS = 10_000L;

    /** 轮询间隔（毫秒）。 */
    private static final long PORT_POLL_INTERVAL_MS = 150L;

    /** 页面加载期间最多显示多久遮罩（毫秒），防止 onPageFinished 不回调时一直黑屏。 */
    private static final long PAGE_LOAD_TIMEOUT_MS = 5_000L;

    /** 页面 ready 后隐藏遮罩的兜底回调标记。 */
    private static final String BRIDGE_NAME = "LiarbarAndroid";

    /**
     * 启动参数：{@code am start -n com.liarsbar.android/.MainActivity --ez host_only true}
     * 让本机以「纯主机」模式运行（页面带 ?join=0，本机玩家不入座）。
     */
    public static final String EXTRA_HOST_ONLY = "host_only";
    /** 开机直接进牌桌（手机专职当主机时用；自动化测试也走这个）。 */
    public static final String EXTRA_AUTO_ENTER = "auto_enter";
    /** 直达房间：--es addr "frp-oak.com:29629" */
    public static final String EXTRA_ADDR = "addr";
    /** 填好地址后自动连接（配合 addr 用） */
    public static final String EXTRA_AUTO_JOIN = "auto_join";

    /** 选头像用的 requestCode。 */
    private static final int REQ_PICK_IMAGE = 1001;
    /** 网页 file input 的回调（选完图要还给它）。 */
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PICK_IMAGE) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private WebView webView;
    private TextView statusText;

    private Handler handler;
    private volatile boolean destroyed = false;
    private long waitStartTime = 0L;

    private volatile boolean portHandled = false;
    private volatile boolean pageLoaded = false;
    private boolean stopDialogShowing = false;

    /** 已经回到最初加载的游戏首页（SPA 用 history API 时防止返回键被网页吞掉）。 */
    private volatile boolean atMainPage = false;

    // ==================================================================
    //  生命周期
    // ==================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        handler = new Handler(Looper.getMainLooper());

        // 游戏主机要常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);
        webView = (WebView) findViewById(R.id.webView);
        statusText = (TextView) findViewById(R.id.statusText);

        configureWebView();

        // 先挂监听，再启动服务，避免错过「已就绪」那一刻
        ServerService.setStateListener(this);
        startHostService();
        waitForServerPort();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;

        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        // 只清掉自己这个监听者，服务端继续在后台跑（用户可以息屏/切后台）
        ServerService.setStateListener(null);

        if (webView != null) {
            try {
                webView.stopLoading();
                webView.setWebChromeClient(null);
                webView.setWebViewClient(null);
                webView.loadUrl("about:blank");
                webView.removeAllViews();
                webView.destroy();
            } catch (Throwable t) {
                Log.w(TAG, "销毁 WebView 失败", t);
            }
            webView = null;
        }

        super.onDestroy();
    }

    // ==================================================================
    //  WebView 配置
    // ==================================================================

    @SuppressWarnings("deprecation")
    private void configureWebView() {
        // 万一系统/ROM 关了硬件加速，WebView 必须同步降级，否则画面会花
        boolean hwAccel = true;
        try {
            android.content.pm.ApplicationInfo ai = getApplicationInfo();
            if (ai != null) {
                hwAccel = (ai.flags & android.content.pm.ApplicationInfo.FLAG_HARDWARE_ACCELERATED) != 0;
            }
        } catch (Throwable ignored) {
            hwAccel = true;
        }
        webView.setLayerType(hwAccel ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_SOFTWARE, null);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);          // localStorage：前端存 token 用
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false); // 音效自动播放
        settings.setLoadsImagesAutomatically(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setUseWideViewPort(true);
        // 严格 1:1，不要为了"塞进屏幕"而整体缩放（否则按钮会被缩到偏出屏幕）
        settings.setLoadWithOverviewMode(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);

        /*
         * 关键兼容项：忽略系统「字体大小 / 显示大小」对网页文字的放大。
         * 华为、荣耀、小米等机型默认字号偏大，WebView 会把网页文字一起放大，
         * 结果固定高度的横屏布局被撑破、按钮被挤到屏幕外点不到。
         * 网页自己有完整的排版，这里必须固定成 100%。
         */
        settings.setTextZoom(100);
        settings.setMinimumFontSize(1);
        settings.setMinimumLogicalFontSize(1);
        settings.setDefaultFontSize(16);
        settings.setDefaultFixedFontSize(16);

        /*
         * 别让系统的「强制深色 / 深色模式」去改我们的配色：
         * 页面本身就是深色主题，被算法反色后会变成看不清的样子。
         */
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                settings.setAlgorithmicDarkeningAllowed(false);
            } else if (Build.VERSION.SDK_INT >= 29) {
                settings.setForceDark(WebSettings.FORCE_DARK_OFF);
            }
        } catch (Throwable ignored) {
            // 个别 ROM 没有这些开关，忽略
        }
        // 页面来自 http://127.0.0.1，不需要读本地文件
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        // 缓存：主机本地页面，直接走内存缓存即可
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        // 本机就是 http://127.0.0.1，不存在混合内容问题，全部放行避免误拦
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setBackgroundColor(0xFF000000);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.addJavascriptInterface(new AndroidBridge(), BRIDGE_NAME);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                if (cm != null) {
                    Log.d(TAG, "JS: " + cm.message() + " @" + cm.sourceId() + ":" + cm.lineNumber());
                }
                return true;
            }

            /**
             * 网页里的 {@code <input type="file">}（换头像）必须由 App 接管，
             * 否则在 WebView 里点了没反应。这里用系统相册/文件选择器。
             */
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("image/*");
                    startActivityForResult(Intent.createChooser(intent, "选择头像"), REQ_PICK_IMAGE);
                    return true;
                } catch (Throwable t) {
                    Log.w(TAG, "打不开图片选择器", t);
                    filePathCallback = null;
                    return false;
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {

            /**
             * 只允许本机地址在 WebView 内打开；外链一律丢给系统浏览器。
             * 返回 true 表示「这个 URL 由我们自己处理了」。
             */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request == null ? null : request.getUrl().toString());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                Log.i(TAG, "onPageStarted: " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "onPageFinished: " + url);
                pageLoaded = true;
                hideStatus();
                /* 启动页是入口：在它上面按返回键才提示退出；在牌桌页按返回键先回启动页 */
                atMainPage = (url != null && url.contains("start.html"));
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame() && error != null) {
                    Log.e(TAG, "主文档加载失败: " + error.getErrorCode() + " " + error.getDescription());
                    showErrorPage("页面加载失败（" + error.getErrorCode() + "）\n" + error.getDescription());
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.e(TAG, "主文档加载失败(旧回调): " + errorCode + " " + description);
                showErrorPage("页面加载失败（" + errorCode + "）\n" + description);
            }
        });
    }

    /**
     * 决定某个 URL 由谁来打开：返回 true = 交给系统（外部 App），false = 留在 WebView 里。
     *
     * <p>关键：<b>所有 http/https 一律留在 App 内</b>。因为"加入房间"本身就是跳到房主地址，
     * 而房主地址可能是端口映射/内网穿透出来的公网地址 —— 以前这里只放行 127.0.0.1 和
     * 192.168/10.x 私网段，导致一点加入就跳出 App 打开浏览器，房间根本进不去。</p>
     */
    private boolean handleUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("about:") || lower.startsWith("data:")
                || lower.startsWith("blob:") || lower.startsWith("file:")) {
            Log.i(TAG, "handleUrl -> 留在 App 内: " + url);
            return false; // 一律留在 App 的 WebView 里
        }
        Log.i(TAG, "handleUrl -> 非网页链接，交给系统处理: " + url);
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "无法打开外部链接", t);
        }
        return true; // 自己处理掉了
    }

    // ==================================================================
    //  启动服务 / 等待端口
    // ==================================================================

    private void startHostService() {
        try {
            Intent intent = new Intent(this, ServerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Throwable t) {
            Log.e(TAG, "启动 ServerService 失败", t);
            showErrorPage("无法启动主机服务\n" + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : (": " + t.getMessage())));
        }
    }

    /**
     * 最多等 10 秒。
     * 正常情况下 {@link #onServerStateChanged()} 会立刻把我们叫醒，
     * 这里只是兜底 + 超时处理。
     */
    private void waitForServerPort() {
        waitStartTime = System.currentTimeMillis();
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed || portHandled) {
                    return;
                }
                if (tryHandlePort()) {
                    return;
                }
                if (System.currentTimeMillis() - waitStartTime >= PORT_WAIT_TIMEOUT_MS) {
                    onServerTimeout();
                    return;
                }
                handler.postDelayed(this, PORT_POLL_INTERVAL_MS);
            }
        });
    }

    /** @return true 表示已经处理（加载页面或展示错误），不用再轮询。 */
    private boolean tryHandlePort() {
        if (portHandled) {
            return true;
        }

        int port = ServerService.getPort();
        if (port > 0) {
            portHandled = true;
            loadGame(port);
            return true;
        }

        if (ServerService.getError() != ServerService.ERR_NONE) {
            portHandled = true;
            showErrorPage(buildStartupErrorHtml());
            return true;
        }

        return false;
    }

    private void onServerTimeout() {
        portHandled = true;
        int port = ServerService.getPort();
        if (port > 0) {
            loadGame(port);
            return;
        }
        showErrorPage("<b>主机启动超时</b><br/>等待了 10 秒仍未拿到监听端口。<br/><br/>"
                + "可能原因：<br/>"
                + "・端口被其它应用占用<br/>"
                + "・系统限制了前台服务<br/>"
                + "・尝试重启应用");
    }

    private String buildStartupErrorHtml() {
        String detail = ServerService.getErrorDetail();
        return "<b>主机启动失败</b><br/><br/>"
                + (detail == null ? "未知原因" : escapeHtml(detail))
                + "<br/><br/>一般是端口被占用，关掉其它占端口的应用后重开即可。";
    }

    /** 加载启动页（创建房间 / 加入房间都在 App 内完成，不需要浏览器）。 */
    private void loadGame(int port) {
        // 主机模式：由 adb 或外部调用方以 --ez host_only true 启动时，本机不入座，
        // 把 4 个位置全部留给其它玩家（旧手机当专用主机时很有用）。
        boolean hostOnly = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_HOST_ONLY, false);
        // 开机直接进桌：手机专职当主机时省一步点击（自动化回归也用它，避免依赖坐标点击）
        boolean autoEnter = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_AUTO_ENTER, false);
        // 直达某个房间：--es addr "frp-oak.com:29629" 会填好地址并直接连过去
        // （异地联机时可以把隧道地址做成一条链接/命令发给朋友）
        String addr = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ADDR);
        boolean autoJoin = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_AUTO_JOIN, false);
        StringBuilder query = new StringBuilder();
        if (hostOnly) {
            query.append("join=0");
        }
        if (autoEnter) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append("auto=enter");
        }
        if (addr != null && addr.length() > 0) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append("addr=").append(android.net.Uri.encode(addr));
            if (autoJoin || !autoEnter) {
                query.append("&auto=join");
            }
        }
        String url = "http://127.0.0.1:" + port + "/start.html"
                + (query.length() > 0 ? "?" + query : "");
        Log.i(TAG, "加载启动页: " + url + (hostOnly ? "（主机模式）" : "")
                + (autoEnter ? "（自动进桌）" : ""));

        if (statusText != null) {
            statusText.setVisibility(View.VISIBLE);
            statusText.setText(getString(R.string.status_starting));
        }
        pageLoaded = false;

        // 兜底：页面迟迟不回调 onPageFinished 也要把画面露出来
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!destroyed && !pageLoaded) {
                    hideStatus();
                }
            }
        }, PAGE_LOAD_TIMEOUT_MS);

        webView.loadUrl(url);
    }

    // ==================================================================
    //  状态提示 / 错误页
    // ==================================================================

    private void hideStatus() {
        if (statusText != null && statusText.getVisibility() == View.VISIBLE) {
            statusText.setVisibility(View.GONE);
        }
    }

    private void showStatus(String text) {
        if (statusText != null) {
            statusText.setText(text);
            statusText.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 失败时用 {@code loadDataWithBaseURL} 显示一张简单错误页。
     * baseUrl 给 null，避免页面继承本机地址的 origin。
     */
    private void showErrorPage(String htmlBody) {
        if (webView == null) {
            showStatus(htmlBody);
            return;
        }

        // 覆盖层直接显示纯文本版本（更稳），同时把 HTML 版载入 WebView
        showStatus("出错了\n\n" + android.text.Html.fromHtml(htmlBody).toString());

        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>body{background:#12151d;color:#e8e2d2;font-family:sans-serif;"
                + "padding:24px;line-height:1.6;font-size:16px}"
                + "b{color:#e05a5a;font-size:20px}</style></head><body>"
                + htmlBody
                + "</body></html>";
        try {
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        } catch (Throwable t) {
            Log.w(TAG, "loadDataWithBaseURL 失败", t);
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    // ==================================================================
    //  服务端状态回调（由 ServerService 在主线程调用）
    // ==================================================================

    @Override
    public void onServerStateChanged() {
        if (destroyed) {
            return;
        }
        tryHandlePort();
    }

    // ==================================================================
    //  返回键
    // ==================================================================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            handleBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 网页能后退就先后退；否则询问是否退出（退出会停掉主机）。 */
    private void handleBack() {
        // SPA 可能用 history.pushState 堆了很多历史，回到首页后就不再让网页吞掉返回键，
        // 否则用户会觉得「怎么按都退不出去」。
        if (!atMainPage && webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        showExitDialog();
    }

    /**
     * 退出确认。
     * 不直接退出：手机是主机，一按返回就退会立刻把所有玩家踢掉。
     */
    private void showExitDialog() {
        if (stopDialogShowing || isFinishing()) {
            return;
        }
        stopDialogShowing = true;
        try {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_exit_title)
                    .setMessage(R.string.dialog_exit_message)
                    .setPositiveButton(R.string.dialog_exit_stop, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            stopHostAndExit();
                        }
                    })
                    .setNegativeButton(R.string.dialog_exit_keep, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            // 关掉界面，主机留在后台继续跑
                            finish();
                        }
                    })
                    .setNeutralButton(R.string.dialog_cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialog) {
                            stopDialogShowing = false;
                        }
                    })
                    .show();
        } catch (Throwable t) {
            // 万一定制 ROM 弹不出对话框，直接停服退出，别把用户卡住
            Log.w(TAG, "显示退出对话框失败，直接退出", t);
            stopDialogShowing = false;
            stopHostAndExit();
        }
    }

    private void stopHostAndExit() {
        try {
            stopService(new Intent(this, ServerService.class));
        } catch (Throwable t) {
            Log.w(TAG, "停止 ServerService 失败", t);
        }
        finish();
    }

    // ==================================================================
    //  JS 桥（前端可选调用，用于提前收起加载遮罩）
    // ==================================================================

    private class AndroidBridge {

        /** 前端在渲染完成后可以调用 {@code window.LiarbarAndroid.onPageReady()}。 */
        @JavascriptInterface
        public void onPageReady() {
            if (handler != null) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        pageLoaded = true;
                        hideStatus();
                    }
                });
            }
        }

        /** 供前端显示 Android 侧的信息（目前只写日志）。 */
        @JavascriptInterface
        public String platformInfo() {
            return "android-" + Build.VERSION.SDK_INT;
        }

        /** 启动页用：本机局域网入口（形如 http://192.168.1.5:8080/），取不到返回空串。 */
        @JavascriptInterface
        public String getLanUrl() {
            String url = ServerService.getLanUrlOrNull();
            return url == null ? "" : url;
        }

        /** 启动页用：搜索附近房间，返回 JSON 数组字符串。 */
        @JavascriptInterface
        public String scanRooms() {
            try {
                return com.liarsbar.core.net.DiscoveryScanner.scanJson(1600);
            } catch (Throwable t) {
                Log.w(TAG, "搜索附近房间失败", t);
                return "[]";
            }
        }
    }
}
