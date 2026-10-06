package com.liarsbar.launcher;

import com.liarsbar.core.LiarbarServer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 骗子酒馆 α版 · 电脑端图形启动器。
 *
 * <p>只做两件事：<b>选端口 / 选网卡把房间服务开起来</b>，以及<b>把地址展示出来方便复制</b>。
 * 电脑在这里只是服务器 —— 不占座位、不当房主，房主是第一个连进来的手机。</p>
 */
public final class Launcher {

    private static final Color BG = new Color(0x1b, 0x24, 0x1c);
    private static final Color FG = new Color(0xe8, 0xf0, 0xe8);
    private static final Color DIM = new Color(0xa8, 0xbd, 0xa8);
    private static final Color GOLD = new Color(0xff, 0xd1, 0x66);
    private static final Color GREEN = new Color(0x5c, 0xbc, 0x60);

    private final JFrame frame = new JFrame("骗子酒馆 α版 · 房间服务端");
    private final JTextField portField = new JTextField("8080", 7);
    private final JComboBox<String> bindBox = new JComboBox<String>();
    private final JButton startBtn = new JButton("启动房间服务端");
    private final JButton stopBtn = new JButton("停止");
    private final JLabel stateLabel = new JLabel("未启动");
    private final JTextField localField = new JTextField();
    private final JTextField lanField = new JTextField();
    private final JTextField publicField = new JTextField();
    private final JLabel publicHint = new JLabel(" ");

    private LiarbarServer server;
    private int runningPort = -1;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {
                    // 用默认外观
                }
                new Launcher().show();
            }
        });
    }

    private void show() {
        /*
         * 关窗口 = 真的关掉服务：以前用 DISPOSE_ON_CLOSE，窗口没了但 JVM 还挂着，
         * 端口一直被占着，再开一次就会跳到别的端口。现在显式 stop() + 退出进程。
         */
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                shutdown();
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                if (server != null) {
                    try {
                        server.stop();
                    } catch (Throwable ignored) {
                        // 退出阶段，忽略
                    }
                    server = null;
                }
            }
        }, "liarbar-launcher-shutdown"));

        frame.setContentPane(buildUi());
        frame.pack();
        frame.setMinimumSize(new Dimension(660, 430));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /** 关窗口时的收尾：先释放端口，再退出进程。 */
    private void shutdown() {
        try {
            stopServer();
        } catch (Throwable ignored) {
            // 忽略
        }
        frame.setVisible(false);
        frame.dispose();
        System.exit(0);
    }

    private JPanel buildUi() {
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(BG);
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JLabel title = new JLabel("骗子酒馆 α版 · 房间服务端");
        title.setForeground(GOLD);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        JLabel sub = new JLabel("在这台电脑上开一个房间，手机连进来就能玩");
        sub.setForeground(DIM);

        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.add(title);
        head.add(Box.createVerticalStrut(3));
        head.add(sub);

        root.add(head, BorderLayout.NORTH);
        root.add(buildCenter(), BorderLayout.CENTER);
        root.add(buildFoot(), BorderLayout.SOUTH);
        return root;
    }

    private JPanel buildCenter() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        /* 端口 + 网卡 + 起停 */
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.add(label("端口"));
        portField.setPreferredSize(new Dimension(72, 26));
        portField.setToolTipText("房间监听的端口，默认 8080；被占用会自动往后找并提示");
        row.add(portField);
        row.add(Box.createHorizontalStrut(12));
        row.add(label("监听网卡"));
        bindBox.setPreferredSize(new Dimension(190, 26));
        bindBox.setToolTipText("0.0.0.0 = 所有网卡（推荐）；也可以只监听某一块");
        fillBindBox();
        row.add(bindBox);
        row.add(Box.createHorizontalStrut(12));
        row.add(startBtn);
        row.add(stopBtn);
        stopBtn.setEnabled(false);

        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 3;
        p.add(row, c);

        stateLabel.setForeground(GREEN);
        c.gridy = 1;
        p.add(stateLabel, c);

        /* 三个地址 */
        c.gridwidth = 1;
        c.gridy = 2;
        c.gridx = 0;
        p.add(label("本机访问"), c);
        c.gridx = 1;
        localField.setEditable(false);
        localField.setPreferredSize(new Dimension(330, 26));
        p.add(localField, c);
        c.gridx = 2;
        p.add(copyBtn(localField, "复制"), c);

        c.gridy = 3;
        c.gridx = 0;
        p.add(label("局域网地址"), c);
        c.gridx = 1;
        lanField.setEditable(false);
        p.add(lanField, c);
        c.gridx = 2;
        p.add(copyBtn(lanField, "复制（同一 WiFi 用）"), c);

        c.gridy = 4;
        c.gridx = 0;
        p.add(label("对外地址"), c);
        c.gridx = 1;
        publicField.setToolTipText("可选。把端口开放到公网后，把公网地址填这里");
        p.add(publicField, c);
        c.gridx = 2;
        p.add(copyBtn(publicField, "复制（外网用）"), c);

        publicHint.setForeground(DIM);
        c.gridy = 5;
        c.gridx = 1;
        c.gridwidth = 2;
        p.add(publicHint, c);

        /* 分隔 + 说明 */
        c.gridy = 6;
        c.gridx = 0;
        c.gridwidth = 3;
        JLabel tip = new JLabel("<html><body style='width:520px'>"
                + "<b>对外地址</b>是给「把端口开放到公网」这种情况留的："
                + "如果你用端口转发 / 内网穿透之类的工具把上面的端口映射出去，"
                + "把映射后的地址填进「对外地址」，房主大厅里的分享地址就会默认用它；"
                + "留空则显示局域网地址。"
                + "</body></html>");
        tip.setForeground(DIM);
        p.add(tip, c);

        c.gridy = 7;
        JLabel tip2 = new JLabel("<html><body style='width:520px'>"
                + "端口被占用时会自动往后找，界面会提示实际用了哪个；"
                + "记得让映射工具的本地端口和这里保持一致。"
                + "</body></html>");
        tip2.setForeground(DIM);
        p.add(tip2, c);

        startBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                startServer();
            }
        });
        stopBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                stopServer();
            }
        });
        publicField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                applyPublicUrl();
            }
        });
        publicField.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                applyPublicUrl();
            }
        });
        return p;
    }

    private JPanel buildFoot() {
        JPanel f = new JPanel();
        f.setOpaque(false);
        f.setLayout(new BoxLayout(f, BoxLayout.Y_AXIS));
        JLabel l1 = new JLabel("这台电脑只当服务器：不占座位、不当房主，房主是第一个连进来的手机。");
        l1.setForeground(FG);
        JLabel l2 = new JLabel("玩法：手机打开「骗子酒馆 α版」→ 点「加入房间」→ 粘贴上面的地址 → 进去的人就能开局。");
        l2.setForeground(DIM);
        f.add(l1);
        f.add(l2);
        return f;
    }

    private JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(FG);
        return l;
    }

    private JButton copyBtn(final JTextField field, final String text) {
        JButton b = new JButton(text);
        b.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                String v = field.getText();
                if (v == null || v.isEmpty()) {
                    return;
                }
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(v), null);
                stateLabel.setText("已复制：" + v);
            }
        });
        return b;
    }

    private void fillBindBox() {
        bindBox.addItem("0.0.0.0（所有网卡，推荐）");
        for (String ip : localIps()) {
            bindBox.addItem(ip);
        }
    }

    private List<String> localIps() {
        List<String> out = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            // 枚举失败就算了
        }
        Collections.sort(out);
        return out;
    }

    private void startServer() {
        if (server != null) {
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(frame, "端口要是数字，比如 8080", "端口不对", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (port < 1 || port > 65535) {
            JOptionPane.showMessageDialog(frame, "端口范围是 1~65535", "端口不对", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String bind = String.valueOf(bindBox.getSelectedItem());
        if (bind != null && bind.contains("（")) {
            bind = bind.substring(0, bind.indexOf('（'));
        }
        try {
            LiarbarServer s = new LiarbarServer(port);
            if (bind != null && !bind.isEmpty() && !"0.0.0.0".equals(bind)) {
                s.setBindAddress(bind);
            }
            s.start();
            String pub = publicField.getText();
            if (pub != null && !pub.trim().isEmpty()) {
                s.setPublicUrl(pub.trim());
            }
            server = s;
            runningPort = s.getPort();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame,
                    "启动失败：" + e.getMessage() + "\n\n端口可能被别的程序占用了，换一个试试。",
                    "启动失败", JOptionPane.ERROR_MESSAGE);
            return;
        }

        localField.setText("http://127.0.0.1:" + runningPort + "/");
        String lan = server.getLanUrl();
        lanField.setText(lan == null ? "（没检测到局域网网卡）" : lan);
        if (runningPort != port) {
            stateLabel.setText("已启动（" + port + " 被占用，实际用了 " + runningPort + "）");
        } else {
            stateLabel.setText("已启动，正在监听 " + runningPort);
        }
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        portField.setEnabled(false);
        bindBox.setEnabled(false);
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        runningPort = -1;
        stateLabel.setText("已停止");
        localField.setText("");
        lanField.setText("");
        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);
        portField.setEnabled(true);
        bindBox.setEnabled(true);
    }

    /** 把「对外地址」同步给服务端（房主大厅的分享地址会默认用它）。 */
    private void applyPublicUrl() {
        String raw = publicField.getText();
        if (raw != null && !raw.trim().isEmpty()) {
            publicHint.setText("已设置为对外地址，房主大厅里的分享地址就是它");
        } else {
            publicHint.setText("留空 = 分享局域网地址");
        }
        if (server == null) {
            return;
        }
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        server.setPublicUrl(raw.trim());
        stateLabel.setText("对外地址已更新");
    }
}
