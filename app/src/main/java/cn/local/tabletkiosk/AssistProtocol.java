package cn.local.tabletkiosk;

import java.util.LinkedHashMap;
import java.util.Map;

/** Assist WebSocket 的文本、音频帧和事件解析。不连接网络。 */
public final class AssistProtocol {
    public static final String WAKE_PHRASE = "okay nabu";
    public static final int SAMPLE_RATE = 16000;
    /** 同一轮对话在结束后继续保留的时间。 */
    public static final long CONVERSATION_HOLD_MS = 120_000;

    private AssistProtocol() {
    }

    /** 音频块：第 1 字节是二进制通道号，后面是 16-bit 小端 PCM。 */
    public static byte[] audioChunk(int handlerId, byte[] pcm, int length) {
        checkHandler(handlerId);
        if (pcm == null || length < 0 || length > pcm.length) throw new IllegalArgumentException("pcm");
        byte[] out = new byte[length + 1];
        out[0] = (byte) handlerId;
        System.arraycopy(pcm, 0, out, 1, length);
        return out;
    }

    /** 结束音频：只发送通道号那一个字节。 */
    public static byte[] audioEnd(int handlerId) {
        checkHandler(handlerId);
        return new byte[] {(byte) handlerId};
    }

    /** 登录消息。令牌原样放进 JSON，调用方不要写日志。 */
    public static String authMessage(String token) {
        Json json = new Json();
        json.put("type", "auth");
        json.put("access_token", token == null ? "" : token);
        return json.build();
    }

    /**
     * 从听写跑到合成。不传 pipeline，使用 Home Assistant 的首选管道。
     * conversationId 为空时不带该字段，表示新的一轮。
     */
    public static String runMessage(int id, String conversationId, String wakePhrase) {
        Json input = new Json();
        input.put("sample_rate", SAMPLE_RATE);
        if (wakePhrase != null && !wakePhrase.isEmpty()) input.put("wake_word_phrase", wakePhrase);
        Json json = new Json();
        json.put("id", id);
        json.put("type", "assist_pipeline/run");
        json.put("start_stage", "stt");
        json.put("end_stage", "tts");
        json.raw("input", input.build());
        if (conversationId != null && !conversationId.isEmpty()) json.put("conversation_id", conversationId);
        return json.build();
    }

    /** 把相对的合成音频地址补成首页所在源站的绝对地址。 */
    public static String absoluteUrl(String origin, String url) {
        if (url == null || url.isEmpty()) return "";
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("https://") || lower.startsWith("http://")) return url;
        if (origin == null || origin.isEmpty()) return url;
        String base = origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
        return url.startsWith("/") ? base + url : base + "/" + url;
    }

    /** 解析一条 WebSocket 文本。无法识别时 kind 为 unknown。 */
    public static Message parse(String json) {
        Node root = read(json);
        if (root == null || root.fields == null) return Message.unknown();
        String type = text(root, "type");
        if ("auth_required".equals(type)) return new Message("auth_required", "", -1, "", "", "", "", "", true);
        if ("auth_ok".equals(type)) return new Message("auth_ok", "", -1, "", "", "", "", "", true);
        if ("auth_invalid".equals(type)) return new Message("auth_invalid", "", -1, "", "", "", "", "", false);
        if ("result".equals(type)) {
            boolean success = !"false".equals(text(root, "success"));
            return new Message("result", "", -1, "", "", "", "", "", success);
        }
        if (!"event".equals(type)) return Message.unknown();
        Node event = root.fields.get("event");
        String eventType = event == null ? "" : text(event, "type");
        Node data = event == null || event.fields == null ? null : event.fields.get("data");
        int handler = -1;
        String conversation = "";
        String transcript = "";
        String reply = "";
        String tts = "";
        String code = "";
        String error = "";
        if (data != null && data.fields != null) {
            conversation = text(data, "conversation_id");
            Node runner = data.fields.get("runner_data");
            if (runner != null) handler = number(runner, "stt_binary_handler_id");
            Node stt = data.fields.get("stt_output");
            if (stt != null) transcript = text(stt, "text");
            reply = speech(data);
            Node ttsOutput = data.fields.get("tts_output");
            if (ttsOutput != null) tts = text(ttsOutput, "url");
            code = text(data, "code");
            error = text(data, "message");
        }
        return new Message("event", eventType, handler, conversation, transcript, reply, tts, error.isEmpty() ? code : error, true);
    }

    /** 一条已解析的服务端消息。 */
    public static final class Message {
        public final String kind;
        public final String eventType;
        public final int handlerId;
        public final String conversationId;
        public final String transcript;
        public final String reply;
        public final String ttsUrl;
        public final String error;
        public final boolean success;

        Message(String kind, String eventType, int handlerId, String conversationId, String transcript,
                String reply, String ttsUrl, String error, boolean success) {
            this.kind = kind;
            this.eventType = eventType;
            this.handlerId = handlerId;
            this.conversationId = conversationId == null ? "" : conversationId;
            this.transcript = transcript == null ? "" : transcript;
            this.reply = reply == null ? "" : reply;
            this.ttsUrl = ttsUrl == null ? "" : ttsUrl;
            this.error = error == null ? "" : error;
            this.success = success;
        }

        static Message unknown() {
            return new Message("unknown", "", -1, "", "", "", "", "", false);
        }
    }

    /** 通道号必须能放进一个字节。 */
    private static void checkHandler(int handlerId) {
        if (handlerId < 0 || handlerId > 255) throw new IllegalArgumentException("handler");
    }

    /** 从意图结果里取出要朗读的纯文本。 */
    private static String speech(Node data) {
        Node intent = data.fields.get("intent_output");
        if (intent == null || intent.fields == null) return "";
        Node response = intent.fields.get("response");
        if (response == null || response.fields == null) return "";
        Node speech = response.fields.get("speech");
        if (speech == null) return "";
        if (speech.scalar != null) return speech.scalar;
        if (speech.fields == null) return "";
        Node plain = speech.fields.get("plain");
        if (plain == null) return "";
        if (plain.scalar != null) return plain.scalar;
        return text(plain, "speech");
    }

    /** 读取字符串字段。缺失时为空。 */
    private static String text(Node node, String key) {
        if (node == null || node.fields == null) return "";
        Node value = node.fields.get(key);
        if (value == null || value.scalar == null) return "";
        return value.scalar;
    }

    /** 读取整数字段。缺失或无法解析时返回 -1。 */
    private static int number(Node node, String key) {
        String value = text(node, key);
        if (value.isEmpty()) return -1;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** JSON 节点。scalar 与 fields 只有一个有效。 */
    private static final class Node {
        final String scalar;
        final Map<String, Node> fields;

        Node(String scalar, Map<String, Node> fields) {
            this.scalar = scalar;
            this.fields = fields;
        }
    }

    /** 解析对象或标量。失败返回 null。 */
    private static Node read(String json) {
        if (json == null) return null;
        int[] index = new int[] {0};
        return readValue(json, index);
    }

    /** 读取当前位置的一个值，数组会被跳过。 */
    private static Node readValue(String json, int[] index) {
        skip(json, index);
        if (index[0] >= json.length()) return null;
        char c = json.charAt(index[0]);
        if (c == '{') return readObject(json, index);
        if (c == '[') {
            if (!skipContainer(json, index, '[', ']')) return null;
            return new Node("", null);
        }
        if (c == '"') {
            String text = readString(json, index);
            return text == null ? null : new Node(text, null);
        }
        int start = index[0];
        while (index[0] < json.length()) {
            char next = json.charAt(index[0]);
            if (next == ',' || next == '}' || next == ']' || next <= ' ') break;
            index[0]++;
        }
        if (start == index[0]) return null;
        String raw = json.substring(start, index[0]);
        return new Node("null".equals(raw) ? "" : raw, null);
    }

    /** 读取对象。 */
    private static Node readObject(String json, int[] index) {
        if (!eat(json, index, '{')) return null;
        Map<String, Node> fields = new LinkedHashMap<>();
        skip(json, index);
        if (eat(json, index, '}')) return new Node(null, fields);
        while (index[0] < json.length()) {
            skip(json, index);
            String key = readString(json, index);
            if (key == null) return null;
            skip(json, index);
            if (!eat(json, index, ':')) return null;
            Node value = readValue(json, index);
            if (value == null) return null;
            fields.put(key, value);
            skip(json, index);
            if (eat(json, index, '}')) return new Node(null, fields);
            if (!eat(json, index, ',')) return null;
        }
        return null;
    }

    /** 跳过一层数组或对象，不解析内容。 */
    private static boolean skipContainer(String json, int[] index, char open, char close) {
        if (!eat(json, index, open)) return false;
        int depth = 1;
        boolean inString = false;
        boolean escaped = false;
        while (index[0] < json.length() && depth > 0) {
            char c = json.charAt(index[0]++);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == open) depth++;
            else if (c == close) depth--;
        }
        return depth == 0;
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

    /** 读取 JSON 字符串。失败返回 null。 */
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
}
