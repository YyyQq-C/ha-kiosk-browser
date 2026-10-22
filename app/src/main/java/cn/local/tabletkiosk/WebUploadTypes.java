package cn.local.tabletkiosk;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** 整理网页上传的 MIME 类型，避免扩展名被系统选择器当成无效过滤条件。 */
final class WebUploadTypes {
    private WebUploadTypes() {
    }

    /** 去重并过滤接受类型，兼容背景图片同时声明 MIME 和扩展名的写法。 */
    static String[] normalize(String[] accepts) {
        Set<String> result = new LinkedHashSet<>();
        if (accepts != null) for (String accept : accepts) {
            if (accept == null) continue;
            for (String part : accept.split(",")) {
                String type = part.trim().toLowerCase(Locale.ROOT);
                switch (type) {
                    case ".jpg": case ".jpeg": type = "image/jpeg"; break;
                    case ".png": type = "image/png"; break;
                    case ".webp": type = "image/webp"; break;
                    case ".gif": type = "image/gif"; break;
                    case ".pdf": type = "application/pdf"; break;
                    case ".json": type = "application/json"; break;
                    case ".txt": type = "text/plain"; break;
                    default: break;
                }
                if ("*/*".equals(type)) return new String[]{"*/*"};
                if (type.matches("[a-z0-9!#$&^_.+-]+/(?:[a-z0-9!#$&^_.+-]+|\\*)")) result.add(type);
            }
        }
        return result.toArray(new String[0]);
    }

    /** 同类图片使用 image/*，具体格式仍由 EXTRA_MIME_TYPES 限制。 */
    static String primary(String[] types) {
        if (types.length == 0) return "*/*";
        if (types.length == 1) return types[0];
        String prefix = types[0].split("/", 2)[0];
        for (String type : types) if (!type.startsWith(prefix + "/")) return "*/*";
        return prefix + "/*";
    }
}
