package com.liarsbar.desktop;

import com.liarsbar.core.FileStaticSource;
import com.liarsbar.core.LiarbarServer;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 桌面端入口：把骗子酒馆当成一个可直接运行的 JAR。
 *
 * <pre>
 * java -jar liarbar-server.jar                 # 默认 8080 端口
 * java -jar liarbar-server.jar --port 9000     # 指定端口
 * java -jar liarbar-server.jar --web ./web     # 从目录读前端（开发用，热更新）
 * java -jar liarbar-server.jar --open          # 启动后用系统浏览器打开
 * </pre>
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        int port = LiarbarServer.DEFAULT_PORT;
        String webDir = null;
        String publicUrl = null;
        String bind = null;
        boolean open = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--port".equals(a) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--web".equals(a) && i + 1 < args.length) {
                webDir = args[++i];
            } else if ("--public-url".equals(a) && i + 1 < args.length) {
                publicUrl = args[++i];
            } else if ("--bind".equals(a) && i + 1 < args.length) {
                bind = args[++i];
            } else if ("--open".equals(a)) {
                open = true;
            } else if ("--help".equals(a) || "-h".equals(a)) {
                usage();
                return;
            } else if (!a.startsWith("-")) {
                // 兼容直接传端口号
                try {
                    port = Integer.parseInt(a);
                } catch (NumberFormatException ignored) {
                    // 忽略
                }
            }
        }

        LiarbarServer server = webDir == null
                ? new LiarbarServer(port)
                : new LiarbarServer(port, new FileStaticSource(webDir));
        if (bind != null && !bind.isEmpty() && !"0.0.0.0".equals(bind) && !"*".equals(bind)) {
            server.setBindAddress(bind);
        }
        server.start();
        if (publicUrl != null) {
            server.setPublicUrl(publicUrl);
        }

        String local = "http://127.0.0.1:" + server.getPort() + "/";
        String lan = server.getLanUrl();

        System.out.println();
        System.out.println("  ==============================================");
        System.out.println("   骗子酒馆 α版 Liar's Bar 服务端已启动");
        System.out.println("  ==============================================");
        System.out.println("   本机访问 : " + local);
        if (lan != null) {
            System.out.println("   局域网   : " + lan  + "   (同一 WiFi 的朋友用这个)");
        } else {
            System.out.println("   局域网   : 未检测到局域网网卡");
        }
        if (publicUrl != null) {
            System.out.println("   对外地址 : " + publicUrl + "   (异地朋友复制这个)");
        } else {
            System.out.println("   远程联机 : 用端口映射工具把本机 " + server.getPort() + " 端口穿透出去即可");
        }
        System.out.println("   房主     : 第一个加入的手机（本程序只当服务器，不占座位）");
        System.out.println("   玩家数   : 2 ~ 4 人，房主在大厅点开始");
        System.out.println("   退出     : Ctrl + C");
        System.out.println();

        if (open) {
            openBrowser(local);
        }

        // 主线程挂住，靠 Ctrl+C 结束
        Thread.currentThread().join();
    }

    private static void usage() {
        System.out.println("用法: java -jar liarbar-server.jar [--port N] [--bind IP] [--public-url URL] [--web DIR] [--open]");
        System.out.println();
        System.out.println("  --port N          监听端口（默认 8080，被占用会自动往后找）");
        System.out.println("  --bind IP         只监听某块网卡（默认 0.0.0.0 所有网卡）");
        System.out.println("  --public-url URL  公网/隧道入口，比如端口映射工具给出的 http://xxx.com:12345/");
        System.out.println("                    填了它，房主大厅里的分享地址默认就是这条对外地址");
        System.out.println("  --web DIR         从目录读前端（开发用，热更新）");
        System.out.println("  --open            启动后用系统浏览器打开");
    }

    private static void openBrowser(String url) {
        try {
            List<String> cmd = new ArrayList<String>();
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                cmd.add("rundll32");
                cmd.add("url.dll,FileProtocolHandler");
                cmd.add(url);
            } else if (os.contains("mac")) {
                cmd.add("open");
                cmd.add(url);
            } else {
                cmd.add("xdg-open");
                cmd.add(url);
            }
            new ProcessBuilder(cmd).start();
        } catch (Exception e) {
            System.out.println("  (自动打开浏览器失败，请手动访问 " + url + ")");
        }
    }

    private Main() {
    }
}
