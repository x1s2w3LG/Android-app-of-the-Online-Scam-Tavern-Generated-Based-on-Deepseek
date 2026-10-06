package com.liarsbar.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 读写实现（零依赖，兼容 Java 8 / Android）。
 * 只支持协议需要的能力：对象、数组、字符串、数字、布尔、null。
 */
public final class Json {

    private Json() {
    }

    public static Map<String, Object> obj() {
        return new LinkedHashMap<String, Object>();
    }

    public static List<Object> arr() {
        return new ArrayList<Object>();
    }

    /** 序列化为 JSON 文本。 */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeTo(sb, value);
        return sb.toString();
    }

    private static void writeTo(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
            return;
        }
        if (o instanceof String) {
            writeString(sb, (String) o);
            return;
        }
        if (o instanceof Boolean) {
            sb.append(((Boolean) o).booleanValue() ? "true" : "false");
            return;
        }
        if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                sb.append(Long.toString((long) d));
            } else {
                sb.append(Double.toString(d));
            }
            return;
        }
        if (o instanceof Number) {
            sb.append(o.toString());
            return;
        }
        if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeTo(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (o instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object v : (Iterable<?>) o) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeTo(sb, v);
            }
            sb.append(']');
            return;
        }
        if (o instanceof Object[]) {
            sb.append('[');
            Object[] a = (Object[]) o;
            for (int i = 0; i < a.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeTo(sb, a[i]);
            }
            sb.append(']');
            return;
        }
        writeString(sb, String.valueOf(o));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        // 中文等直接输出，socket 侧统一按 UTF-8 编码
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /** 解析 JSON 文本。 */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.parseValue();
        p.skipWs();
        if (!p.eof()) {
            throw new IllegalArgumentException("JSON 尾部有多余内容");
        }
        return v;
    }

    /** 解析并断言是对象。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object o = parse(text);
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException("JSON 顶层不是对象");
        }
        return (Map<String, Object>) o;
    }

    // ---------------------------------------------------------------- 工具

    public static String str(Map<String, Object> m, String key) {
        Object o = m.get(key);
        return o == null ? null : String.valueOf(o);
    }

    public static String str(Map<String, Object> m, String key, String def) {
        String s = str(m, key);
        return s == null ? def : s;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String key) {
        Object o = m.get(key);
        if (o instanceof List) {
            return (List<Object>) o;
        }
        return null;
    }

    /** 取嵌套对象（不是对象就返回 null）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Map<String, Object> m, String key) {
        Object o = m == null ? null : m.get(key);
        if (o instanceof Map) {
            return (Map<String, Object>) o;
        }
        return null;
    }

    public static int intValue(Map<String, Object> m, String key, int def) {
        Object o = m.get(key);
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        return def;
    }

    // ---------------------------------------------------------------- 解析器

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s == null ? "" : s;
        }

        boolean eof() {
            return i >= s.length();
        }

        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        Object parseValue() {
            skipWs();
            if (eof()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return parseObj();
                case '[':
                    return parseArr();
                case '"':
                    return parseString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return parseNumber();
            }
        }

        private void expect(String word) {
            if (!s.startsWith(word, i)) {
                throw new IllegalArgumentException("JSON 非法字面量，位置 " + i);
            }
            i += word.length();
        }

        private Map<String, Object> parseObj() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++; // {
            skipWs();
            if (!eof() && s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                if (eof() || s.charAt(i) != '"') {
                    throw new IllegalArgumentException("JSON 对象的键必须是字符串，位置 " + i);
                }
                String k = parseString();
                skipWs();
                if (eof() || s.charAt(i) != ':') {
                    throw new IllegalArgumentException("JSON 对象缺少冒号，位置 " + i);
                }
                i++;
                m.put(k, parseValue());
                skipWs();
                if (eof()) {
                    throw new IllegalArgumentException("JSON 对象未闭合");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == '}') {
                    i++;
                    return m;
                }
                throw new IllegalArgumentException("JSON 对象出现非法字符 '" + c + "'，位置 " + i);
            }
        }

        private List<Object> parseArr() {
            List<Object> l = new ArrayList<Object>();
            i++; // [
            skipWs();
            if (!eof() && s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(parseValue());
                skipWs();
                if (eof()) {
                    throw new IllegalArgumentException("JSON 数组未闭合");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == ']') {
                    i++;
                    return l;
                }
                throw new IllegalArgumentException("JSON 数组出现非法字符 '" + c + "'，位置 " + i);
            }
        }

        private String parseString() {
            StringBuilder sb = new StringBuilder();
            i++; // 开头的引号
            while (true) {
                if (eof()) {
                    throw new IllegalArgumentException("JSON 字符串未闭合");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (eof()) {
                    throw new IllegalArgumentException("JSON 转义未完成");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (i + 4 > s.length()) {
                            throw new IllegalArgumentException("JSON \\u 转义不完整");
                        }
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("JSON 非法转义 \\" + e);
                }
            }
        }

        private Object parseNumber() {
            int start = i;
            if (!eof() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
                i++;
            }
            boolean floating = false;
            while (!eof()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                    floating = floating || c == '.' || c == 'e' || c == 'E';
                    i++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, i);
            if (num.isEmpty()) {
                throw new IllegalArgumentException("JSON 期望一个值，位置 " + start);
            }
            if (floating) {
                return Double.valueOf(num);
            }
            return Long.valueOf(num);
        }
    }
}
