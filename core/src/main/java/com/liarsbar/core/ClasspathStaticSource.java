package com.liarsbar.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 从 classpath 的 {@code /web/} 读取静态资源（桌面端 jar 用）。
 * Android 端会传入基于 {@code assets/web/} 的实现。
 */
public final class ClasspathStaticSource implements LiarbarServer.StaticProvider {

    @Override
    public byte[] read(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        InputStream in = null;
        try {
            in = LiarbarServer.class.getResourceAsStream("/web/" + path);
            if (in == null) {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if (cl != null) {
                    in = cl.getResourceAsStream("web/" + path);
                }
            }
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }
}
