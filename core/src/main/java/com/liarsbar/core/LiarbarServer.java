package com.liarsbar.core;

import com.liarsbar.core.net.DiscoveryResponder;
import com.liarsbar.core.net.HttpWsServer;
import com.liarsbar.core.service.GameService;

import java.io.IOException;

/**
 * 骗子酒馆服务端：HTTP 静态页面 + WebSocket 游戏服，共用同一个端口。
 *
 * <p>桌面端（{@code java -jar}）和 Android（内嵌 APK）共用这一份实现。</p>
 */
public final class LiarbarServer {

    /** 静态资源提供者：path 形如 {@code index.html} / {@code js/app.js}，找不到返回 null。 */
    public interface StaticProvider extends com.liarsbar.core.net.StaticProvider {
    }

    public static final int DEFAULT_PORT = 8080;

    private static final long TICK_INTERVAL_MS = 500L;
    private static final long PING_INTERVAL_MS = 10_000L;

    private final int requestedPort;
    private final StaticProvider provider;
    private final GameService service;

    private HttpWsServer http;
    private Thread ticker;
    private com.liarsbar.core.net.DiscoveryResponder discovery;    private volatile boolean running;
    private volatile String lanUrl;
    private volatile int port = -1;
    private String bindAddress;

    public LiarbarServer() {
        this(DEFAULT_PORT, new ClasspathStaticSource());
    }

    public LiarbarServer(int port) {
        this(port, new ClasspathStaticSource());
    }

    public LiarbarServer(int port, StaticProvider provider) {
        this.requestedPort = port;
        this.provider = provider == null ? new ClasspathStaticSource() : provider;
        this.service = new GameService(null);
    }

    /** 绑定端口并开始服务；立即返回，内部线程处理连接。 */
    public void start() throws IOException {
        if (running) {
            return;
        }
        http = new HttpWsServer(requestedPort, provider, service);
        if (bindAddress != null) {
            http.setBindAddress(bindAddress);
        }
        http.setAvatarSource(service);
        http.start();
        port = http.getPort();

        String ip = HttpWsServer.findLanIp();
        lanUrl = (ip == null) ? null : ("http://" + ip + ":" + port + "/");
        service.setHttpPort(port);
        service.setLanUrl(lanUrl);

        running = true;
        ticker = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "liarbar-ticker");
        ticker.setDaemon(true);
        ticker.start();

        // 局域网自动发现（失败不影响游戏）
        discovery = new DiscoveryResponder(new DiscoveryResponder.Info() {
            public int httpPort() {
                return port;
            }

            public int players() {
                return service.getPlayerCount();
            }

            public String phase() {
                return service.getPhase();
            }
        });
        discovery.start();
    }

    private void loop() {
        long lastPing = 0;
        while (running) {
            try {
                service.tick();
                long now = System.currentTimeMillis();
                if (now - lastPing >= PING_INTERVAL_MS) {
                    lastPing = now;
                    service.pingAll();
                }
                Thread.sleep(TICK_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // 单个 tick 出错不能拖垮服务器
                System.err.println("[liarbar] tick 异常: " + t);
            }
        }
    }

    public void stop() {
        running = false;
        if (ticker != null) {
            ticker.interrupt();
        }
        if (discovery != null) {
            discovery.stop();
        }
        service.closeAll();
        if (http != null) {
            http.stop();
        }
    }

    /** 实际监听端口（自动避让后会与传入值不同）。 */
    public int getPort() {
        return port;
    }

    /** 局域网入口，形如 {@code http://192.168.1.5:8080/}；取不到网卡时返回 null。 */
    public String getLanUrl() {
        return lanUrl;
    }

    /** 只监听哪块网卡（默认所有网卡 0.0.0.0）。必须在 start() 之前设置。 */
    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }

    /**
     * 告诉服务端"门外还有一个公网地址"（比如端口映射 / 内网穿透给出的
     * {@code http://example.com:29629/}）。
     * 会写进 state.publicUrl，让房主大厅里的分享地址默认就是这条对外地址，
     * 朋友们复制粘贴就能连进来 —— 电脑端自己不占座位、也不当房主。
     */
    public void setPublicUrl(String publicUrl) {
        service.setPublicUrl(publicUrl);
    }

    /** 当前房间内玩家数（含掉线保留座位的）。 */
    public int getPlayerCount() {
        return service.getPlayerCount();
    }

    /** 供外壳显示状态用。 */
    public String getPhase() {
        return service.getPhase();
    }
}
