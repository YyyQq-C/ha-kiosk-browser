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
