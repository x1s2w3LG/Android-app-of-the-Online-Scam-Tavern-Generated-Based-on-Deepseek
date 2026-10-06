package com.liarsbar.core.util;

/**
 * 极简 Base64 解码（Android 低版本没有 java.util.Base64，所以自带一份）。
 */
public final class Base64 {

    private static final int[] TABLE = new int[128];

    static {
        for (int i = 0; i < TABLE.length; i++) {
            TABLE[i] = -1;
        }
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < chars.length(); i++) {
            TABLE[chars.charAt(i)] = i;
        }
        TABLE['='] = -2;
    }

    private Base64() {
    }

    /** 解码；遇到非法字符抛 IllegalArgumentException。 */
    public static byte[] decode(String s) {
        if (s == null) {
            return null;
        }
        int len = s.length();
        // 允许有空白的换行（有些前端会分行）
        byte[] out = new byte[len / 4 * 3 + 3];
        int buf = 0;
        int bits = 0;
        int n = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') {
                continue;
            }
            if (c > 127) {
                throw new IllegalArgumentException("非 ASCII 的 Base64 字符");
            }
            int v = TABLE[c];
            if (v == -2) {          // '=' 之后都是填充，直接结束
                break;
            }
            if (v < 0) {
                throw new IllegalArgumentException("非法 Base64 字符: " + c);
            }
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[n++] = (byte) ((buf >> bits) & 0xFF);
            }
        }
        if (n == out.length) {
            return out;
        }
        byte[] exact = new byte[n];
        System.arraycopy(out, 0, exact, 0, n);
        return exact;
    }
}
