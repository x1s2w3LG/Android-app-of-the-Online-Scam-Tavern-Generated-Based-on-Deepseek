package com.liarsbar.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 从文件系统目录读取静态资源（开发时热更新用），找不到时回退到 classpath。
 * 用法：{@code java -jar liarbar-server.jar --web <目录>}
 */
public final class FileStaticSource implements LiarbarServer.StaticProvider {

    private final File root;
    private final LiarbarServer.StaticProvider fallback;

    public FileStaticSource(String dir) {
        this(new File(dir), new ClasspathStaticSource());
    }

    public FileStaticSource(File root, LiarbarServer.StaticProvider fallback) {
        this.root = root;
        this.fallback = fallback;
    }

    @Override
    public byte[] read(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        File f = new File(root, path.replace('/', File.separatorChar));
        if (f.isFile()) {
            InputStream in = null;
            try {
                in = new FileInputStream(f);
                ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            } catch (Exception e) {
                // 落到 fallback
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
        return fallback == null ? null : fallback.read(path);
    }
}
