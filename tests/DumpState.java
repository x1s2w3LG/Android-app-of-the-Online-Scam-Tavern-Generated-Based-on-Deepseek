import com.liarsbar.core.LiarbarServer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** 抓一份 state 原文，用来核对字段。 */
public final class DumpState {
    public static void main(String[] args) throws Exception {
        // 带参数 = 连到已有的服务器（比如手机里的那个），不带就自己起一个
        boolean external = args.length > 0;
        int port = external ? Integer.parseInt(args[0]) : 18222;
        LiarbarServer server = null;
        if (!external) {
            server = new LiarbarServer(port);
            server.start();
        }
        final StringBuilder last = new StringBuilder();
        WebSocket ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), new WebSocket.Listener() {
                    private final StringBuilder part = new StringBuilder();

                    public void onOpen(WebSocket w) {
                        w.request(1);
                    }

                    public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean fin) {
                        part.append(data);
                        if (fin) {
                            String s = part.toString();
                            part.setLength(0);
                            if (s.contains("\"t\":\"state\"")) {
                                synchronized (last) {
                                    last.setLength(0);
                                    last.append(s);
                                }
                            }
                        }
                        w.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);
        ws.sendText("{\"t\":\"join\",\"name\":\"检查者\"}", true);
        Thread.sleep(600);
        if (!external) {
            ws.sendText("{\"t\":\"add-bot\"}", true);
            Thread.sleep(900);
            ws.sendText("{\"t\":\"start\"}", true);
            Thread.sleep(900);
        } else {
            Thread.sleep(1500);
        }

        synchronized (last) {
            String s = last.toString();
            // 只打印 players 部分，方便看
            int i = s.indexOf("\"players\"");
            System.out.println(i < 0 ? s : s.substring(i, Math.min(s.length(), i + 900)));
        }
        ws.abort();
        if (server != null) {
            server.stop();
        }
    }
}
