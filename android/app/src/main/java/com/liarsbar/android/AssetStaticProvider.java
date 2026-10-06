package com.liarsbar.android;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.liarsbar.core.LiarbarServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 把 APK 里的 {@code assets/web/&lt;path&gt;} 交给 core 的 HTTP 服务端。
 *
 * <p>契约见 PROTOCOL.md 第 6 节：
 * {@code public interface StaticProvider { byte[] read(String path); }}
 * path 形如 {@code "index.html"} / {@code "js/app.js"}，找不到返回 {@code null}。
 *
 * <p>查找顺序：
 * <ol>
 *   <li>{@code assets/web/<path>}  —— 手机上的正常路径（由 tools/sync-web.ps1 同步进来）</li>
 *   <li>{@code classpath:/web/<path>} —— 万一资源被塞进了 jar/classes 的兜底路径</li>
 *   <li>{@code null}</li>
 * </ol>
 *
 * <p>注意：{@link AssetManager#open(String)} 对「目录」或「不存在」都会抛
 * {@link IOException}，所以必须 try/catch，不能让它冒泡到 HTTP 线程，
 * 否则一个 404 请求会把连接打断。
 */
public class AssetStaticProvider implements LiarbarServer.StaticProvider {

    private static final String TAG = "AssetStaticProvider";

    /** APK assets 里的网页根目录。 */
    private static final String ASSET_ROOT = "web/";

    /** classpath 兜底前缀。 */
    private static final String CLASSPATH_PREFIX = "web/";

    private final AssetManager assets;

    private volatile boolean loggedDiag = false;

    public AssetStaticProvider(Context context) {
        // 用 getApplicationContext() 避免持有 Activity 引用（服务端生命周期比 Activity 长）
        Context app = (context == null) ? null : context.getApplicationContext();
        this.assets = (app == null) ? null : app.getAssets();
    }

    @Override
    public byte[] read(String path) {
        String normalized = normalize(path);
        if (normalized == null || normalized.isEmpty()) {
            return null;
        }

        byte[] data = readFromAssets(normalized);
        if (data != null) {
            return data;
        }
        return readFromClasspath(normalized);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 去掉开头的 {@code /}、反斜杠和 {@code ./}，
     * 同时挡掉 {@code ../} 这类目录穿越（静态资源服务必须防）。
     */
    private static String normalize(String path) {
        if (path == null) {
            return null;
        }
        String p = path.trim();
        if (p.isEmpty()) {
            return null;
        }
        // 去掉查询串（core 正常会剥掉，这里再防一手）
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        int h = p.indexOf('#');
        if (h >= 0) {
            p = p.substring(0, h);
        }
        // URL 解码（%20 之类）
        p = urlDecode(p);
        // 统一分隔符
        p = p.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        if (p.isEmpty()) {
            return null;
        }
        // 目录穿越防护
        if (p.contains("..")) {
            Log.w(TAG, "拒绝可疑路径: " + path);
            return null;
        }
        return p;
    }

    private static String urlDecode(String s) {
        if (s.indexOf('%') < 0 && s.indexOf('+') < 0) {
            return s;
        }
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Throwable t) {
            // URLDecoder 对非法 % 序列会抛 IllegalArgumentException
            return s;
        }
    }

    private byte[] readFromAssets(String path) {
        if (assets == null) {
            logDiag(path, null);
            return null;
        }
        InputStream in = null;
        try {
            in = assets.open(ASSET_ROOT + path);
            return readAll(in);
        } catch (IOException e) {
            // 目录 / 不存在都会走到这里，属于正常情况（返回 null 让上层回退）
            logDiag(path, e);
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "读取 assets 失败: " + path, t);
            logDiag(path, t);
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /** 只在第一次读取失败时打印一次详细诊断，避免刷屏。 */
    private void logDiag(String path, Throwable t) {
        if (loggedDiag) {
            return;
        }
        loggedDiag = true;
        try {
            StringBuilder sb = new StringBuilder(256);
            sb.append("读取 assets 失败诊断: path=").append(path)
              .append(" assets=").append(assets == null ? "null" : "非空");
            if (assets != null) {
                sb.append(" list(\"\")=").append(java.util.Arrays.toString(assets.list("")));
                sb.append(" list(\"web\")=").append(java.util.Arrays.toString(assets.list("web")));
            }
            Log.w(TAG, sb.toString(), t);
        } catch (Throwable ignored) {
            // 诊断本身不能抛异常
        }
    }

    private byte[] readFromClasspath(String path) {
        InputStream in = null;
        try {
            ClassLoader cl = AssetStaticProvider.class.getClassLoader();
            if (cl == null) {
                return null;
            }
            in = cl.getResourceAsStream("/" + CLASSPATH_PREFIX + path);
            if (in == null) {
                return null;
            }
            return readAll(in);
        } catch (Throwable t) {
            Log.w(TAG, "读取 classpath 失败: " + path, t);
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /** 手写读写循环，避免依赖 Java 版本相关的 API。 */
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }
}
