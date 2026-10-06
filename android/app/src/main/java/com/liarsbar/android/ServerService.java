package com.liarsbar.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.liarsbar.core.LiarbarServer;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 前台服务：把纯 Java 的 {@link LiarbarServer} 跑在手机里，让手机既是主机又是玩家。
 *
 * <p>保活手段：
 * <ul>
 *   <li>{@code startForeground} + 常驻通知（"正在运行"通知在 API 33+ 也无法被划掉）</li>
 *   <li>{@link PowerManager#PARTIAL_WAKE_LOCK}：屏幕熄灭后 CPU 继续跑，WebSocket 不断</li>
 *   <li>{@link WifiManager.WifiLock}（HIGH_PERF）：避免 WiFi 省电降速导致丢包</li>
 *   <li>{@code START_STICKY}：被系统杀掉后尽量重启</li>
 * </ul>
 *
 * <p>端口协商：交给 core。{@code HttpWsServer} 会从请求端口起连续尝试最多 20 个端口，
 * 所以正常传 8080 即可；只有在整段区间都不可用（极端情况）才会换候选起点重试。
 * 最终一律以 {@link LiarbarServer#getPort()} 返回的实际端口为准。
 *
 * <p>与 Activity 通信：用静态 volatile 字段（{@link #PORT} / {@link #LAN_URL} / {@link #ERROR}）
 * 加一个静态回调，故意不用 LocalBroadcastManager，避免引入 androidx。
 */
public class ServerService extends Service {

    private static final String TAG = "ServerService";

    // ================= 对外（给 MainActivity 用）的静态状态 =================

    /** 与 MainActivity 约定的启动失败码。 */
    public static final int ERR_NONE = 0;
    public static final int ERR_BIND_FAILED = 1;
    public static final int ERR_EXCEPTION = 2;

    /** 服务端还没起来时的占位端口。 */
    public static final int NO_PORT = 0;

    private static final int STATE_IDLE = 0;
    private static final int STATE_STARTING = 1;
    private static final int STATE_RUNNING = 2;
    private static final int STATE_FAILED = 3;

    /** 实际监听端口；0 表示还没就绪。static volatile 保证跨线程可见。 */
    private static volatile int PORT = NO_PORT;

    /** 当前状态（STATE_*）。 */
    private static volatile int STATE = STATE_IDLE;

    /** 失败码（ERR_*）。 */
    private static volatile int ERROR = ERR_NONE;

    /** 局域网入口，例如 http://192.168.1.5:8080/；不可用时为 null。 */
    private static volatile String LAN_URL = null;

    /** core 抛出的异常文本，方便在界面上展示原因。 */
    private static volatile String ERROR_DETAIL = null;

    /** 轻量状态回调：MainActivity 注册，服务端就绪/失败时立刻通知，省去纯轮询。 */
    public interface ServerStateListener {
        void onServerStateChanged();
    }

    private static volatile ServerStateListener sListener = null;

    public static void setStateListener(ServerStateListener listener) {
        sListener = listener;
    }

    public static int getPort() {
        return PORT;
    }

    public static boolean isRunning() {
        return STATE == STATE_RUNNING && PORT > NO_PORT;
    }

    public static int getError() {
        return ERROR;
    }

    public static String getErrorDetail() {
        return ERROR_DETAIL;
    }

    public static String getLanUrlOrNull() {
        return LAN_URL;
    }

    // ================= 通知 =================

    public static final String CHANNEL_ID = "liarbar_host";
    public static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_STOP = "com.liarsbar.android.action.STOP_HOST";

    private static final int REQ_STOP = 101;
    private static final int REQ_CONTENT = 102;

    // ================= 实例字段 =================

    /** 首选端口。core 会从这个端口起自动向后避让，正常情况永远用 8080。 */
    private static final int DEFAULT_PORT = 8080;

    /**
     * 候选起点。第 0 个是首选；后面的只在「8080 起连续 20 个端口都被占」这种
     * 极端情况下才会用到，故意跳开 Android 系统常用端口段。
     */
    private static final int[] PORT_CANDIDATES = {DEFAULT_PORT, 8180, 18080, 28080};

    private static final int MAX_BIND_ROUNDS = PORT_CANDIDATES.length;

    private LiarbarServer server;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private Handler mainHandler;
    private Thread worker;

    /** 保证只启动一次（系统重复调用 onStartCommand 时）。 */
    private final AtomicBoolean starting = new AtomicBoolean(false);

    /** onDestroy 已经在跑，worker 结束后不要再 post 任何东西。 */
    private volatile boolean destroyed = false;

    // ================= 生命周期 =================

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        // 通知渠道必须在 startForeground 之前建好（API 26+ 强制）
        ensureChannel();
        acquireLocks();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 立刻进入前台，避免 Context.startForegroundService 的 5 秒超时 ANR
        startForegroundSafely(buildNotification(getString(R.string.notif_text_starting), false, true));

        // 通知里的「停止主机」
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.i(TAG, "收到停止主机指令");
            PORT = NO_PORT;
            LAN_URL = null;
            STATE = STATE_IDLE;
            notifyListener();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (starting.compareAndSet(false, true)) {
            startServerInBackground();
        } else {
            Log.i(TAG, "服务已在启动/运行中，忽略重复启动请求");
        }

        // 被系统杀掉后重启（重启时 intent 为 null，仍会走上面的启动分支）
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;

        LiarbarServer s = server;
        server = null;
        if (s != null) {
            try {
                s.stop();
            } catch (Throwable t) {
                Log.w(TAG, "停止服务端时出错", t);
            }
        }

        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }

        releaseLocks();
        stopForegroundSafely();

        PORT = NO_PORT;
        LAN_URL = null;
        ERROR = ERR_NONE;
        ERROR_DETAIL = null;
        STATE = STATE_IDLE;
        notifyListener();

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        // 不需要绑定：Activity 通过静态字段拿端口
        return null;
    }

    // ================= 启动服务端 =================

    private void startServerInBackground() {
        STATE = STATE_STARTING;
        ERROR = ERR_NONE;
        ERROR_DETAIL = null;
        PORT = NO_PORT;
        LAN_URL = null;

        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                bindAndStart();
            }
        }, "liarbar-server-boot");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 在后台线程里启动 core 服务端。
     *
     * <p>端口避让交给 core：{@code com.liarsbar.core.net.HttpWsServer} 会从请求端口
     * 起连续尝试最多 20 个端口，所以正常情况下传 8080 就能成功，实际端口以
     * {@link LiarbarServer#getPort()} 为准。这里只在一整段区间都不可用时，
     * 换一个远离常见服务的区段再试一次（最多 {@link #MAX_BIND_ROUNDS} 轮）。
     */
    private void bindAndStart() {
        int lastBound = -1;
        for (int round = 0; round < MAX_BIND_ROUNDS; round++) {
            final int requested = PORT_CANDIDATES[round];

            LiarbarServer candidateServer = null;
            try {
                candidateServer = new LiarbarServer(requested, new AssetStaticProvider(getApplicationContext()));
                candidateServer.start();

                int actual = candidateServer.getPort();
                if (actual <= 0) {
                    // core 没抛异常却没给出有效端口，视为这一轮失败
                    Log.w(TAG, "在端口 " + requested + " 启动后 getPort() 返回 " + actual);
                    disposeCandidate(candidateServer);
                    continue;
                }

                if (destroyed) {
                    // 启动过程中用户已经关掉了服务：收尾并放弃
                    Log.i(TAG, "服务已被销毁，放弃刚启动的服务端");
                    disposeCandidate(candidateServer);
                    return;
                }

                server = candidateServer;
                final String lanUrl = safeLanUrl(candidateServer);
                Log.i(TAG, "主机已启动: requestedPort=" + requested + " actualPort=" + actual
                        + " lanUrl=" + lanUrl);

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) {
                            return;
                        }
                        PORT = actual;
                        LAN_URL = lanUrl;
                        ERROR = ERR_NONE;
                        ERROR_DETAIL = null;
                        STATE = STATE_RUNNING;
                        updateNotification(buildNotification(buildRunningText(actual, lanUrl), true, false));
                        notifyListener();
                    }
                });
                return;
            } catch (IOException e) {
                // 这一段端口全被占用（或者 core 内部启动失败）：换区段重试
                Log.w(TAG, "以端口 " + requested + " 为起点启动失败: " + e.getMessage());
                lastBound = requested;
                disposeCandidate(candidateServer);
            } catch (Throwable t) {
                // 预期之外的错误：不重试，直接把原因显示给用户
                Log.e(TAG, "启动服务端时发生异常", t);
                disposeCandidate(candidateServer);
                final String detail = describe(t);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) {
                            return;
                        }
                        PORT = NO_PORT;
                        LAN_URL = null;
                        ERROR = ERR_EXCEPTION;
                        ERROR_DETAIL = detail;
                        STATE = STATE_FAILED;
                        updateNotification(buildNotification("启动失败：" + detail, false, false));
                        notifyListener();
                    }
                });
                stopSelf();
                return;
            }
        }

        // 所有候选起点都失败
        final String detail = (lastBound > 0)
                ? ("端口 " + lastBound + " 起连续多段都被占用")
                : "候选端口全部被占用";
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                PORT = NO_PORT;
                LAN_URL = null;
                ERROR = ERR_BIND_FAILED;
                ERROR_DETAIL = detail;
                STATE = STATE_FAILED;
                updateNotification(buildNotification("启动失败：" + detail, false, false));
                notifyListener();
            }
        });
        stopSelf();
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + ((msg == null || msg.isEmpty()) ? "" : (": " + msg));
    }

    /** 启动失败的实例要收尾，避免占住端口/线程。 */
    private static void disposeCandidate(LiarbarServer s) {
        if (s != null) {
            try {
                s.stop();
            } catch (Throwable ignored) {
                // 忽略
            }
        }
    }

    private static String safeLanUrl(LiarbarServer s) {
        try {
            return s.getLanUrl();
        } catch (Throwable t) {
            Log.w(TAG, "getLanUrl() 失败", t);
            return null;
        }
    }

    private static String buildRunningText(int port, String lanUrl) {
        if (lanUrl != null && lanUrl.length() > 0) {
            return lanUrl;
        }
        return "http://<本机IP>:" + port + "/";
    }

    // ================= 通知 =================

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return; // API 26 以下不需要渠道
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (nm.getNotificationChannel(CHANNEL_ID) != null) {
            return; // 已存在就不要再建，否则会清掉用户的自定义设置
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notif_channel_desc));
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String text, boolean running, boolean ongoing) {
        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setSmallIcon(R.drawable.ic_stat_host)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setWhen(System.currentTimeMillis())
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(ongoing)
                .setAutoCancel(false)
                .setContentIntent(contentIntent());

        if (running) {
            // 只在真正跑起来后才给「停止主机」，避免启动中误触
            // icon 传 0：Android 7+ 的操作按钮不再显示图标
            builder.addAction(new Notification.Action.Builder(
                    0, getString(R.string.notif_stop), stopIntent()).build());
        }

        return builder.build();
    }

    /** 点通知回到游戏界面。 */
    private PendingIntent contentIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, REQ_CONTENT, intent, pendingFlags());
    }

    /** 通知里的「停止主机」。 */
    private PendingIntent stopIntent() {
        Intent intent = new Intent(this, ServerService.class);
        intent.setAction(ACTION_STOP);
        return PendingIntent.getService(this, REQ_STOP, intent, pendingFlags());
    }

    private static int pendingFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private void updateNotification(Notification notification) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        try {
            nm.notify(NOTIFICATION_ID, notification);
        } catch (Throwable t) {
            Log.w(TAG, "更新通知失败", t);
        }
    }

    private void startForegroundSafely(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            // 某些定制 ROM 在后台启动前台服务会抛异常；退化成普通通知也要跑起来
            Log.e(TAG, "startForeground 失败", t);
            updateNotification(notification);
        }
    }

    private void stopForegroundSafely() {
        try {
            stopForeground(true);
        } catch (Throwable t) {
            Log.w(TAG, "stopForeground 失败", t);
        }
    }

    // ================= 电源 / WiFi 锁 =================

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "liarbar:host");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
                Log.i(TAG, "PARTIAL_WAKE_LOCK 已获取");
            }
        } catch (Throwable t) {
            Log.w(TAG, "获取 WakeLock 失败（继续运行）", t);
        }

        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "liarbar:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
                Log.i(TAG, "WifiLock(HIGH_PERF) 已获取");
            }
        } catch (Throwable t) {
            // Android 10+ 对 HIGH_PERF 有额外限制，失败就退到 FULL
            Log.w(TAG, "获取 WifiLock(HIGH_PERF) 失败，尝试 FULL", t);
            try {
                WifiManager wm = (WifiManager) getApplicationContext()
                        .getSystemService(WIFI_SERVICE);
                if (wm != null) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "liarbar:wifi");
                    wifiLock.setReferenceCounted(false);
                    wifiLock.acquire();
                }
            } catch (Throwable t2) {
                Log.w(TAG, "获取 WifiLock 失败（继续运行）", t2);
            }
        }
    }

    private void releaseLocks() {
        if (wifiLock != null) {
            try {
                if (wifiLock.isHeld()) {
                    wifiLock.release();
                }
            } catch (Throwable t) {
                Log.w(TAG, "释放 WifiLock 失败", t);
            }
            wifiLock = null;
        }
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            } catch (Throwable t) {
                Log.w(TAG, "释放 WakeLock 失败", t);
            }
            wakeLock = null;
        }
    }

    // ================= 其它 =================

    private void notifyListener() {
        final ServerStateListener listener = sListener;
        if (listener == null) {
            return;
        }
        if (mainHandler != null && Looper.myLooper() != mainHandler.getLooper()) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        listener.onServerStateChanged();
                    } catch (Throwable t) {
                        Log.w(TAG, "状态回调异常", t);
                    }
                }
            });
        } else {
            try {
                listener.onServerStateChanged();
            } catch (Throwable t) {
                Log.w(TAG, "状态回调异常", t);
            }
        }
    }
}
