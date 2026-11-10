package cn.local.tabletkiosk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** 右侧应用栏里的外部应用列表：只保存包名，按逗号分隔存储；不依赖 Android，便于单测。 */
public final class ExternalApps {
    /** 应用栏最多放这么多个应用，避免面板过长。 */
    public static final int MAX = 12;
    private static final int MAX_PACKAGE_LENGTH = 255;
    /** Android 包名：至少两段，每段以字母开头，只含字母、数字和下划线。 */
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");

    private ExternalApps() {
    }

    /** 是否是合法的 Android 包名。 */
    public static boolean validPackage(String name) {
        return name != null && name.length() <= MAX_PACKAGE_LENGTH && PACKAGE.matcher(name).matches();
    }

    /** 读回保存的文字。空项、非法包名和重复项丢掉，最多保留 MAX 个，返回不可修改的列表。 */
    public static List<String> parse(String text) {
        if (text == null || text.trim().isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String part : text.split(",")) {
            String name = part.trim();
            if (!validPackage(name) || out.contains(name)) continue;
            out.add(name);
            if (out.size() >= MAX) break;
        }
        return Collections.unmodifiableList(out);
    }

    /** 转成保存用的文字。包名里不会出现逗号。 */
    public static String join(List<String> apps) {
        return apps == null ? "" : String.join(",", apps);
    }

    /** 加到末尾。重复、非法或已满时原样返回。 */
    public static List<String> add(List<String> apps, String name) {
        List<String> current = apps == null ? Collections.emptyList() : apps;
        if (!validPackage(name) || current.contains(name) || current.size() >= MAX) return current;
        List<String> out = new ArrayList<>(current);
        out.add(name);
        return Collections.unmodifiableList(out);
    }

    /** 移除一项，其余顺序不变。 */
    public static List<String> remove(List<String> apps, String name) {
        List<String> current = apps == null ? Collections.emptyList() : apps;
        if (!current.contains(name)) return current;
        List<String> out = new ArrayList<>(current);
        out.remove(name);
        return Collections.unmodifiableList(out);
    }

    /** 复制成不可修改的列表，并按 parse 的规则清理。 */
    static List<String> copyOf(List<String> apps) {
        return parse(join(apps));
    }
}
