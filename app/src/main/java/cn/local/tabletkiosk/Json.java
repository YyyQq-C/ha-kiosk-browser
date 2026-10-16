package cn.local.tabletkiosk;

/** 生成简单 JSON 对象的小工具，避免在纯逻辑中依赖 Android 的 org.json。 */
final class Json {
    private final StringBuilder sb = new StringBuilder("{");
    private boolean first = true;

    /** 写入字符串字段；null 写成 JSON null。 */
    Json put(String key, String value) {
        return raw(key, value == null ? "null" : quote(value));
    }

    /** 写入整数字段。 */
    Json put(String key, int value) {
        return raw(key, Integer.toString(value));
    }

    /** 写入布尔字段。 */
    Json put(String key, boolean value) {
        return raw(key, value ? "true" : "false");
    }

    /** 写入已序列化的 JSON 片段，例如嵌套对象或数组。 */
    Json raw(String key, String json) {
        if (!first) sb.append(',');
        first = false;
        sb.append(quote(key)).append(':').append(json);
        return this;
    }

    /** 结束对象并返回文本。 */
    String build() {
        return sb.toString() + "}";
    }

    /** 生成字符串数组。 */
    static String array(String... values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            out.append(quote(values[i]));
        }
        return out.append(']').toString();
    }

    /**
     * 读取一层 JSON 对象。字符串、数字、true、false、null 都收成文本。
     * 遇到嵌套对象或数组时返回 null。
     */
    static java.util.Map<String, String> readObject(String json) {
        if (json == null) return null;
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        int[] index = new int[] {0};
        skip(json, index);
        if (!eat(json, index, '{')) return null;
        skip(json, index);
        if (eat(json, index, '}')) return map;
        while (index[0] < json.length()) {
            String key = readString(json, index);
            if (key == null) return null;
            skip(json, index);
            if (!eat(json, index, ':')) return null;
            skip(json, index);
            String value = readValue(json, index);
            if (value == null) return null;
            map.put(key, value);
            skip(json, index);
            if (eat(json, index, '}')) return map;
            if (!eat(json, index, ',')) return null;
            skip(json, index);
        }
        return null;
    }

    /** 跳过空白。 */
    private static void skip(String json, int[] index) {
        while (index[0] < json.length() && json.charAt(index[0]) <= ' ') index[0]++;
    }

    /** 当前位置是预期字符时前进一格。 */
    private static boolean eat(String json, int[] index, char expected) {
        if (index[0] < json.length() && json.charAt(index[0]) == expected) {
            index[0]++;
            return true;
        }
        return false;
    }

    /** 读取一个 JSON 字符串，失败返回 null。 */
    private static String readString(String json, int[] index) {
        if (!eat(json, index, '"')) return null;
        StringBuilder out = new StringBuilder();
        while (index[0] < json.length()) {
            char c = json.charAt(index[0]++);
            if (c == '"') return out.toString();
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (index[0] >= json.length()) return null;
            char escaped = json.charAt(index[0]++);
            switch (escaped) {
                case '"':
                case '\\':
                case '/':
                    out.append(escaped);
                    break;
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'u':
                    if (index[0] + 4 > json.length()) return null;
                    try {
                        out.append((char) Integer.parseInt(json.substring(index[0], index[0] + 4), 16));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                    index[0] += 4;
                    break;
                default:
                    return null;
            }
        }
        return null;
    }

    /** 读取一层标量。嵌套结构不支持。 */
    private static String readValue(String json, int[] index) {
        if (index[0] >= json.length()) return null;
        char c = json.charAt(index[0]);
        if (c == '"') return readString(json, index);
        if (c == '{' || c == '[') return null;
        int start = index[0];
        while (index[0] < json.length()) {
            char next = json.charAt(index[0]);
            if (next == ',' || next == '}' || next <= ' ') break;
            index[0]++;
        }
        if (start == index[0]) return null;
        String raw = json.substring(start, index[0]);
        if ("null".equals(raw)) return "";
        return raw;
    }

    /** 按 JSON 规则转义并加引号，中文原样保留。 */
    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }
}
