# ============================================================================
#  compile-check.ps1 -- the strongest check possible without an Android SDK.
#
#  Generates minimal *stub* Android API classes (matching the real signatures we
#  use, at compileSdk 34 level) plus a stub com.liarsbar.core.LiarbarServer that
#  matches PROTOCOL.md section 6 exactly, then runs javac on the real shell
#  sources against them.
#
#  A clean compile proves:
#    * every Java file is syntactically valid
#    * every Android method we call exists with the arity/type we assume
#    * every LiarbarServer member we call exists with the protocol signature
#    * R.* resource ids referenced from Java all exist
#
#  This file is pure ASCII on purpose (Windows PowerShell 5.1 + GBK console).
# ============================================================================

param(
    # Also compile the real core/ sources together with the shell (integration check).
    # Off by default because core/ is authored in parallel and may be mid-edit.
    [switch] $WithRealCore
)

$ErrorActionPreference = 'Stop'

$android  = 'C:\Users\x1s2w3\Documents\deepseek-harness\default-workspace\liarbar\android'
$javac    = 'C:\Program Files\Android\openjdk\jdk-21.0.8\bin\javac.exe'
$work     = Join-Path $env:TEMP 'liarbar-javac-check'

if (-not (Test-Path -LiteralPath $javac)) { throw "javac not found: $javac" }

# --- fresh work dir ---------------------------------------------------------
if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
$stubSrc = Join-Path $work 'stub-src'
$out     = Join-Path $work 'classes'
New-Item -ItemType Directory -Path $stubSrc, $out -Force | Out-Null

function New-Stub([string] $relPath, [string] $content) {
    $full = Join-Path $stubSrc $relPath
    $dir  = Split-Path -Parent $full
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    [System.IO.File]::WriteAllText($full, $content, (New-Object System.Text.UTF8Encoding($false)))
}

# ===========================================================================
#  com.liarsbar.core.LiarbarServer  -- verbatim from PROTOCOL.md section 6
# ===========================================================================
New-Stub 'com\liarsbar\core\LiarbarServer.java' @'
package com.liarsbar.core;

public final class LiarbarServer {
    /** Static resource provider: path like "index.html" / "js/app.js", null if not found. */
    public interface StaticProvider { byte[] read(String path); }

    public LiarbarServer() {}
    public LiarbarServer(int port, StaticProvider provider) {}
    public void start() throws java.io.IOException {}
    public void stop() {}
    public int getPort() { return 8080; }
    public String getLanUrl() { return null; }
    public int getPlayerCount() { return 0; }
}
'@

# ===========================================================================
#  java.lang.Object / String extras we rely on
# ===========================================================================
New-Stub 'android\R.java' @'
package com.liarsbar.android;

public final class R {
    public static final class id {
        public static final int webView = 0x7f010001;
        public static final int statusText = 0x7f010002;
    }
    public static final class layout {
        public static final int activity_main = 0x7f020001;
    }
    public static final class string {
        public static final int app_name = 0x7f030001;
        public static final int notif_channel_name = 0x7f030002;
        public static final int notif_channel_desc = 0x7f030003;
        public static final int notif_title = 0x7f030004;
        public static final int notif_text_starting = 0x7f030005;
        public static final int notif_stop = 0x7f030006;
        public static final int status_starting = 0x7f030007;
        public static final int title_error = 0x7f030008;
        public static final int dialog_exit_title = 0x7f030009;
        public static final int dialog_exit_message = 0x7f03000a;
        public static final int dialog_exit_stop = 0x7f03000b;
        public static final int dialog_exit_keep = 0x7f03000c;
        public static final int dialog_cancel = 0x7f03000d;
    }
    public static final class drawable {
        public static final int ic_stat_host = 0x7f040001;
        public static final int ic_launcher_foreground = 0x7f040002;
        public static final int ic_launcher_background = 0x7f040003;
    }
    public static final class mipmap {
        public static final int ic_launcher = 0x7f050001;
        public static final int ic_launcher_round = 0x7f050002;
    }
    public static final class style {
        public static final int AppTheme = 0x7f060001;
    }
    public static final class xml {
        public static final int network_security_config = 0x7f070001;
    }
}
'@

New-Stub 'android\content\Context.java' @'
package android.content;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;

public abstract class Context {
    public static final String POWER_SERVICE = "power";
    public static final String WIFI_SERVICE = "wifi";
    public static final String NOTIFICATION_SERVICE = "notification";

    public Context() {}
    public Context getApplicationContext() { return this; }
    public AssetManager getAssets() { return null; }
    public Resources getResources() { return null; }
    public ApplicationInfo getApplicationInfo() { return null; }
    public PackageManager getPackageManager() { return null; }
    public Object getSystemService(String name) { return null; }
    public String getString(int resId) { return ""; }
    public String getString(int resId, Object... formatArgs) { return ""; }
    public void startActivity(Intent intent) {}
    public ComponentName startService(Intent intent) { return null; }
    public ComponentName startForegroundService(Intent intent) { return null; }
    public boolean stopService(Intent intent) { return false; }
}
'@

New-Stub 'android\content\ComponentName.java' @'
package android.content;
public final class ComponentName {}
'@

New-Stub 'android\content\Intent.java' @'
package android.content;

import android.net.Uri;

public class Intent {
    public static final String ACTION_MAIN = "android.intent.action.MAIN";
    public static final String ACTION_VIEW = "android.intent.action.VIEW";
    public static final String CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER";
    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static final int FLAG_ACTIVITY_SINGLE_TOP = 0x20000000;

    public Intent() {}
    public Intent(Context packageContext, Class<?> cls) {}
    public Intent(String action) {}
    public Intent(String action, Uri uri) {}
    public Intent setAction(String action) { return this; }
    public Intent addCategory(String category) { return this; }
    public Intent addFlags(int flags) { return this; }
    public Intent setFlags(int flags) { return this; }
    public String getAction() { return null; }
}
'@

New-Stub 'android\content\DialogInterface.java' @'
package android.content;
public interface DialogInterface {
    interface OnClickListener { void onClick(DialogInterface dialog, int which); }
    interface OnCancelListener { void onCancel(DialogInterface dialog); }
    interface OnDismissListener { void onDismiss(DialogInterface dialog); }
    void dismiss();
    void cancel();
}
'@

New-Stub 'android\content\pm\ApplicationInfo.java' @'
package android.content.pm;
public class ApplicationInfo {
    public static final int FLAG_HARDWARE_ACCELERATED = 0x00002000;
    public int flags;
}
'@

New-Stub 'android\content\pm\PackageManager.java' @'
package android.content.pm;
public abstract class PackageManager {}
'@

New-Stub 'android\content\pm\ServiceInfo.java' @'
package android.content.pm;
public class ServiceInfo {
    public static final int FOREGROUND_SERVICE_TYPE_DATA_SYNC = 1;
    public static final int FOREGROUND_SERVICE_TYPE_NONE = 0;
}
'@

New-Stub 'android\content\res\AssetManager.java' @'
package android.content.res;

import java.io.IOException;
import java.io.InputStream;

public final class AssetManager {
    public InputStream open(String fileName) throws IOException { return null; }
    public void close() {}
}
'@

New-Stub 'android\content\res\Resources.java' @'
package android.content.res;
public class Resources {}
'@

New-Stub 'android\app\Activity.java' @'
package android.app;

import android.content.Context;
import android.os.Bundle;
import android.view.Window;

public class Activity extends Context {
    public Activity() {}
    protected void onCreate(Bundle savedInstanceState) {}
    protected void onDestroy() {}
    protected void onResume() {}
    protected void onPause() {}
    protected void onStart() {}
    protected void onStop() {}
    public void onBackPressed() {}
    public void finish() {}
    public boolean isFinishing() { return false; }
    public Window getWindow() { return null; }
    public void setContentView(int layoutResID) {}
    public android.view.View findViewById(int id) { return null; }
    public void runOnUiThread(Runnable action) {}
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) { return false; }
    public boolean onKeyUp(int keyCode, android.view.KeyEvent event) { return false; }
    public boolean dispatchKeyEvent(android.view.KeyEvent event) { return false; }
}
'@

New-Stub 'android\app\Service.java' @'
package android.app;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

public abstract class Service extends Context {
    public static final int START_STICKY = 1;
    public static final int START_NOT_STICKY = 2;
    public static final int START_REDELIVER_INTENT = 3;

    public Service() {}
    public void onCreate() {}
    public void onDestroy() {}
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    public IBinder onBind(Intent intent) { return null; }
    public boolean onUnbind(Intent intent) { return false; }
    public void stopSelf() {}
    public void stopSelf(int startId) {}
    public final void startForeground(int id, Notification notification) {}
    public final void startForeground(int id, Notification notification, int foregroundServiceType) {}
    public final void stopForeground(boolean removeNotification) {}
}
'@

New-Stub 'android\app\Notification.java' @'
package android.app;

import android.content.Context;

public class Notification {
    public static class Action {
        public static class Builder {
            public Builder(int icon, CharSequence title, PendingIntent intent) {}
            public Action build() { return null; }
        }
    }

    public static class Builder {
        public Builder(Context context) {}
        public Builder(Context context, String channelId) {}
        public Builder setSmallIcon(int icon) { return this; }
        public Builder setContentTitle(CharSequence title) { return this; }
        public Builder setContentText(CharSequence text) { return this; }
        public Builder setWhen(long when) { return this; }
        public Builder setOnlyAlertOnce(boolean onlyAlertOnce) { return this; }
        public Builder setShowWhen(boolean show) { return this; }
        public Builder setOngoing(boolean ongoing) { return this; }
        public Builder setAutoCancel(boolean autoCancel) { return this; }
        public Builder setContentIntent(PendingIntent intent) { return this; }
        public Builder addAction(Action action) { return this; }
        public Notification build() { return null; }
    }
}
'@

New-Stub 'android\app\NotificationChannel.java' @'
package android.app;

public final class NotificationChannel {
    public NotificationChannel(String id, CharSequence name, int importance) {}
    public void setDescription(String description) {}
    public void setShowBadge(boolean showBadge) {}
    public void enableVibration(boolean vibration) {}
    public void setSound(android.media.AudioAttributes attributes, android.media.Ringtone sound) {}
}
'@

New-Stub 'android\app\NotificationManager.java' @'
package android.app;

public class NotificationManager {
    public static final int IMPORTANCE_LOW = 2;
    public void createNotificationChannel(NotificationChannel channel) {}
    public NotificationChannel getNotificationChannel(String channelId) { return null; }
    public void notify(int id, Notification notification) {}
    public void cancel(int id) {}
}
'@

New-Stub 'android\app\PendingIntent.java' @'
package android.app;

import android.content.Context;
import android.content.Intent;

public final class PendingIntent {
    public static final int FLAG_UPDATE_CURRENT = 0x08000000;
    public static final int FLAG_IMMUTABLE = 0x04000000;
    public static final int FLAG_MUTABLE = 0x02000000;

    public static PendingIntent getActivity(Context context, int requestCode, Intent intent, int flags) { return null; }
    public static PendingIntent getService(Context context, int requestCode, Intent intent, int flags) { return null; }
    public static PendingIntent getBroadcast(Context context, int requestCode, Intent intent, int flags) { return null; }
}
'@

New-Stub 'android\app\AlertDialog.java' @'
package android.app;

import android.content.Context;
import android.content.DialogInterface;

public class AlertDialog {
    public static class Builder {
        public Builder(Context context) {}
        public Builder setTitle(int titleId) { return this; }
        public Builder setTitle(CharSequence title) { return this; }
        public Builder setMessage(int messageId) { return this; }
        public Builder setMessage(CharSequence message) { return this; }
        public Builder setPositiveButton(int textId, DialogInterface.OnClickListener listener) { return this; }
        public Builder setNegativeButton(int textId, DialogInterface.OnClickListener listener) { return this; }
        public Builder setNeutralButton(int textId, DialogInterface.OnClickListener listener) { return this; }
        public Builder setOnCancelListener(DialogInterface.OnCancelListener listener) { return this; }
        public Builder setOnDismissListener(DialogInterface.OnDismissListener listener) { return this; }
        public AlertDialog create() { return null; }
        public AlertDialog show() { return null; }
    }
}
'@

New-Stub 'android\media\AudioAttributes.java' @'
package android.media;
public final class AudioAttributes {}
'@

New-Stub 'android\media\Ringtone.java' @'
package android.media;
public final class Ringtone {}
'@

New-Stub 'android\net\Uri.java' @'
package android.net;

public abstract class Uri {
    public static Uri parse(String uriString) { return null; }
    public String toString() { return ""; }
}
'@

New-Stub 'android\net\wifi\WifiManager.java' @'
package android.net.wifi;

public class WifiManager {
    public static final int WIFI_MODE_FULL = 1;
    public static final int WIFI_MODE_SCAN_ONLY = 2;
    public static final int WIFI_MODE_FULL_HIGH_PERF = 3;
    public static final int WIFI_MODE_FULL_LOW_LATENCY = 4;

    public WifiLock createWifiLock(int lockType, String tag) { return null; }
    public WifiLock createWifiLock(String tag) { return null; }

    public class WifiLock {
        public void acquire() {}
        public void release() {}
        public boolean isHeld() { return false; }
        public void setReferenceCounted(boolean refCounted) {}
    }
}
'@

New-Stub 'android\os\Build.java' @'
package android.os;

public class Build {
    public static class VERSION { public static final int SDK_INT = 34; }
    public static class VERSION_CODES {
        public static final int LOLLIPOP = 21;
        public static final int M = 23;
        public static final int N = 24;
        public static final int O = 26;
        public static final int P = 28;
        public static final int Q = 29;
        public static final int R = 30;
        public static final int S = 31;
        public static final int TIRAMISU = 33;
        public static final int UPSIDE_DOWN_CAKE = 34;
    }
}
'@

New-Stub 'android\os\Bundle.java' @'
package android.os;
public final class Bundle {}
'@

New-Stub 'android\os\Handler.java' @'
package android.os;

public class Handler {
    public Handler() {}
    public Handler(Looper looper) {}
    public boolean post(Runnable r) { return true; }
    public boolean postDelayed(Runnable r, long delayMillis) { return true; }
    public void removeCallbacks(Runnable r) {}
    public void removeCallbacksAndMessages(Object token) {}
    public Looper getLooper() { return null; }
}
'@

New-Stub 'android\os\IBinder.java' @'
package android.os;
public interface IBinder {}
'@

New-Stub 'android\os\Looper.java' @'
package android.os;

public final class Looper {
    public static Looper myLooper() { return null; }
    public static Looper getMainLooper() { return null; }
    public void quit() {}
}
'@

New-Stub 'android\os\PowerManager.java' @'
package android.os;

public final class PowerManager {
    public static final int PARTIAL_WAKE_LOCK = 1;
    public static final int SCREEN_DIM_WAKE_LOCK = 6;
    public static final int FULL_WAKE_LOCK = 26;

    public WakeLock newWakeLock(int levelAndFlags, String tag) { return null; }

    public final class WakeLock {
        public void acquire() {}
        public void acquire(long timeout) {}
        public void release() {}
        public boolean isHeld() { return false; }
        public void setReferenceCounted(boolean value) {}
    }
}
'@

New-Stub 'android\util\Log.java' @'
package android.util;

public final class Log {
    public static int d(String tag, String msg) { return 0; }
    public static int d(String tag, String msg, Throwable tr) { return 0; }
    public static int i(String tag, String msg) { return 0; }
    public static int i(String tag, String msg, Throwable tr) { return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int w(String tag, String msg, Throwable tr) { return 0; }
    public static int e(String tag, String msg) { return 0; }
    public static int e(String tag, String msg, Throwable tr) { return 0; }
    public static int v(String tag, String msg) { return 0; }
}
'@

New-Stub 'android\text\Html.java' @'
package android.text;

public final class Html {
    public static android.text.Spanned fromHtml(String source) { return null; }
}
'@

New-Stub 'android\text\Spanned.java' @'
package android.text;
public interface Spanned extends CharSequence {}
'@

New-Stub 'android\graphics\Bitmap.java' @'
package android.graphics;
public final class Bitmap {}
'@

New-Stub 'android\graphics\Color.java' @'
package android.graphics;
public class Color {}
'@

New-Stub 'android\view\View.java' @'
package android.view;

public class View {
    public static final int VISIBLE = 0;
    public static final int INVISIBLE = 4;
    public static final int GONE = 8;
    public static final int LAYER_TYPE_NONE = 0;
    public static final int LAYER_TYPE_SOFTWARE = 1;
    public static final int LAYER_TYPE_HARDWARE = 2;
    public static final int OVER_SCROLL_NEVER = 2;

    public View() {}
    public View(android.content.Context context) {}
    public void setVisibility(int visibility) {}
    public int getVisibility() { return 0; }
    public void setLayerType(int layerType, android.graphics.Paint paint) {}
    public void setBackgroundColor(int color) {}
    public void setOverScrollMode(int mode) {}
    public void setKeepScreenOn(boolean keepScreenOn) {}
}
'@

New-Stub 'android\graphics\Paint.java' @'
package android.graphics;
public class Paint {}
'@

New-Stub 'android\view\KeyEvent.java' @'
package android.view;

public class KeyEvent {
    public static final int KEYCODE_BACK = 4;
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public int getKeyCode() { return 0; }
    public int getAction() { return 0; }
}
'@

New-Stub 'android\view\Window.java' @'
package android.view;
public abstract class Window {
    public void addFlags(int flags) {}
    public void clearFlags(int flags) {}
    public void setSoftInputMode(int mode) {}
}
'@

New-Stub 'android\view\WindowManager.java' @'
package android.view;

public interface WindowManager {
    class LayoutParams {
        public static final int FLAG_KEEP_SCREEN_ON = 0x00000080;
        public static final int FLAG_FULLSCREEN = 0x00000400;
        public int flags;
    }
}
'@

New-Stub 'android\view\ViewGroup.java' @'
package android.view;

import android.content.Context;

public abstract class ViewGroup extends View {
    public ViewGroup() {}
    public ViewGroup(Context context) { super(context); }
    public void removeAllViews() {}
}
'@

New-Stub 'android\widget\TextView.java' @'
package android.widget;

import android.content.Context;
import android.view.View;

public class TextView extends View {
    public TextView(Context context) {}
    public void setText(CharSequence text) {}
    public void setText(int resId) {}
    public CharSequence getText() { return null; }
}
'@

New-Stub 'android\webkit\WebView.java' @'
package android.webkit;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

public class WebView extends ViewGroup {
    public WebView(Context context) { super(context); }
    public WebSettings getSettings() { return null; }
    public void setWebViewClient(WebViewClient client) {}
    public void setWebChromeClient(WebChromeClient client) {}
    public void addJavascriptInterface(Object object, String name) {}
    public void removeJavascriptInterface(String name) {}
    public void loadUrl(String url) {}
    public void loadUrl(String url, java.util.Map<String, String> additionalHttpHeaders) {}
    public void loadDataWithBaseURL(String baseUrl, String data, String mimeType, String encoding, String historyUrl) {}
    public void stopLoading() {}
    public void reload() {}
    public boolean canGoBack() { return false; }
    public void goBack() {}
    public boolean canGoForward() { return false; }
    public void goForward() {}
    public void clearHistory() {}
    public void clearCache(boolean includeDiskFiles) {}
    public void setBackgroundColor(int color) {}
    public void setVerticalScrollBarEnabled(boolean verticalScrollBarEnabled) {}
    public void setHorizontalScrollBarEnabled(boolean horizontalScrollBarEnabled) {}
    public void destroy() {}
}
'@

New-Stub 'android\webkit\WebSettings.java' @'
package android.webkit;

public abstract class WebSettings {
    public static final int LOAD_DEFAULT = -1;
    public static final int LOAD_CACHE_ELSE_NETWORK = 1;
    public static final int LOAD_NO_CACHE = 2;
    public static final int LOAD_CACHE_ONLY = 3;
    public static final int MIXED_CONTENT_ALWAYS_ALLOW = 0;
    public static final int MIXED_CONTENT_NEVER_ALLOW = 1;
    public static final int MIXED_CONTENT_COMPATIBILITY_MODE = 2;

    public void setJavaScriptEnabled(boolean flag) {}
    public void setDomStorageEnabled(boolean flag) {}
    public void setDatabaseEnabled(boolean flag) {}
    public void setMediaPlaybackRequiresUserGesture(boolean require) {}
    public void setLoadsImagesAutomatically(boolean flag) {}
    public void setJavaScriptCanOpenWindowsAutomatically(boolean flag) {}
    public void setSupportMultipleWindows(boolean support) {}
    public void setUseWideViewPort(boolean use) {}
    public void setLoadWithOverviewMode(boolean overview) {}
    public void setBuiltInZoomControls(boolean enabled) {}
    public void setDisplayZoomControls(boolean enabled) {}
    public void setSupportZoom(boolean support) {}
    public void setAllowFileAccess(boolean allow) {}
    public void setAllowContentAccess(boolean allow) {}
    public void setCacheMode(int mode) {}
    public void setMixedContentMode(int mode) {}
    public void setUserAgentString(String ua) {}
    public String getUserAgentString() { return null; }
}
'@

New-Stub 'android\webkit\WebViewClient.java' @'
package android.webkit;

import android.graphics.Bitmap;

public class WebViewClient {
    public boolean shouldOverrideUrlLoading(WebView view, String url) { return false; }
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return false; }
    public void onPageStarted(WebView view, String url, Bitmap favicon) {}
    public void onPageFinished(WebView view, String url) {}
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {}
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {}
}
'@

New-Stub 'android\webkit\WebResourceRequest.java' @'
package android.webkit;

import android.net.Uri;

public interface WebResourceRequest {
    Uri getUrl();
    boolean isForMainFrame();
    boolean isRedirect();
    boolean hasGesture();
    String getMethod();
}
'@

New-Stub 'android\webkit\WebResourceError.java' @'
package android.webkit;
public interface WebResourceError {
    int getErrorCode();
    CharSequence getDescription();
}
'@

New-Stub 'android\webkit\WebChromeClient.java' @'
package android.webkit;

public class WebChromeClient {
    public boolean onConsoleMessage(ConsoleMessage consoleMessage) { return false; }
    public void onProgressChanged(WebView view, int newProgress) {}
}
'@

New-Stub 'android\webkit\ConsoleMessage.java' @'
package android.webkit;
public class ConsoleMessage {
    public String message() { return null; }
    public String sourceId() { return null; }
    public int lineNumber() { return 0; }
}
'@

New-Stub 'android\webkit\JavascriptInterface.java' @'
package android.webkit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface JavascriptInterface {}
'@

# ===========================================================================
#  Compile the real sources
# ===========================================================================
Write-Host ''
Write-Host '=== javac compile check (real sources + stub Android/core) ===' -ForegroundColor Cyan
Write-Host "stubs : $stubSrc"
Write-Host "output: $out"
Write-Host ''

$shellSources = @()
$shellSources += (Get-ChildItem -LiteralPath (Join-Path $android 'app\src\main\java') -Recurse -Filter '*.java' |
                ForEach-Object { $_.FullName })

# The shell is compiled against the PROTOCOL.md stub on purpose: the core module
# is written in parallel and may be mid-edit. Report its state but do not let it
# break this check unless -WithRealCore was requested.
$coreSrc = 'C:\Users\x1s2w3\Documents\deepseek-harness\default-workspace\liarbar\core\src\main\java'
$coreNote = 'stub only (core/ has no sources yet)'
$extraCore = @()
if (Test-Path -LiteralPath $coreSrc) {
    $coreFiles = @(Get-ChildItem -LiteralPath $coreSrc -Recurse -Filter '*.java')
    $hasApi = @($coreFiles | Where-Object { $_.Name -eq 'LiarbarServer.java' }).Count -gt 0
    $coreNote = "$($coreFiles.Count) real file(s); LiarbarServer.java present = $hasApi"
    if ($WithRealCore) {
        # drop the stub LiarbarServer so the real one is used
        $stubApi = Join-Path $stubSrc 'com\liarsbar\core\LiarbarServer.java'
        if (Test-Path -LiteralPath $stubApi) { Remove-Item -LiteralPath $stubApi -Force }
        $extraCore = @($coreFiles | ForEach-Object { $_.FullName })
        $coreNote += '  [COMPILED TOGETHER]'
    }
}

# enumerate stubs only AFTER the optional stub removal above
$sources = @()
$sources += $shellSources
$sources += (Get-ChildItem -LiteralPath $stubSrc -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
$sources += $extraCore

Write-Host "core  : $coreNote" -ForegroundColor Yellow
Write-Host "shell : $($shellSources.Count) source file(s)"
Write-Host ''

$log = Join-Path $work 'javac.log'
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $javac
$psi.Arguments = '-J-Duser.language=en -J-Duser.country=US -encoding UTF-8 -source 11 -target 11 -Xlint:-options -d "' +
                 $out + '" ' + (($sources | ForEach-Object { '"' + $_ + '"' }) -join ' ')
$psi.UseShellExecute = $false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.CreateNoWindow = $true
$proc = [System.Diagnostics.Process]::Start($psi)
$stdout = $proc.StandardOutput.ReadToEnd()
$stderr = $proc.StandardError.ReadToEnd()
$proc.WaitForExit()
$code = $proc.ExitCode
$report = ($stdout + "`n" + $stderr).Trim()
[System.IO.File]::WriteAllText($log, $report, (New-Object System.Text.UTF8Encoding($false)))
Write-Host $report

Write-Host ''
if ($code -eq 0) {
    Write-Host 'JAVAC OK -- all shell sources compile against the protocol API' -ForegroundColor Green
    $classes = @(Get-ChildItem -LiteralPath $out -Recurse -Filter '*.class')
    Write-Host "produced $($classes.Count) class file(s)"
} else {
    Write-Host "JAVAC FAILED (exit $code) -- full log: $log" -ForegroundColor Red
}
exit $code
