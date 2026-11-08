package cn.local.tabletkiosk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** 外部应用列表：包名校验、保存格式、去重和数量上限。 */
public class ExternalAppsTest {
    /** 只接受 Android 包名格式。 */
    @Test
    public void validatesPackageNames() {
        assertTrue(ExternalApps.validPackage("com.spotify.music"));
        assertTrue(ExternalApps.validPackage("a.b"));
        assertTrue(ExternalApps.validPackage("com.example_app.v2"));
        assertFalse(ExternalApps.validPackage(null));
        assertFalse(ExternalApps.validPackage(""));
        assertFalse(ExternalApps.validPackage("spotify"));
        assertFalse(ExternalApps.validPackage("com..spotify"));
        assertFalse(ExternalApps.validPackage("com.spotify."));
        assertFalse(ExternalApps.validPackage("1com.spotify"));
        assertFalse(ExternalApps.validPackage("com.1spotify"));
        assertFalse(ExternalApps.validPackage("com.spotify music"));
        assertFalse(ExternalApps.validPackage("com.spotify,music"));
        assertFalse(ExternalApps.validPackage("spotify:track"));
    }

    /** 逗号分隔的文本读回列表，丢掉空项、非法项和重复项，保持原顺序。 */
    @Test
    public void parsesSavedText() {
        assertEquals(Arrays.asList("com.spotify.music", "org.videolan.vlc"),
                ExternalApps.parse(" com.spotify.music , bad name,,org.videolan.vlc,com.spotify.music"));
        assertEquals(Collections.emptyList(), ExternalApps.parse(""));
        assertEquals(Collections.emptyList(), ExternalApps.parse(null));
    }

    /** 保存格式能原样读回。 */
    @Test
    public void joinRoundTrips() {
        List<String> apps = Arrays.asList("com.spotify.music", "org.videolan.vlc");
        assertEquals("com.spotify.music,org.videolan.vlc", ExternalApps.join(apps));
        assertEquals(apps, ExternalApps.parse(ExternalApps.join(apps)));
        assertEquals("", ExternalApps.join(Collections.emptyList()));
        assertEquals("", ExternalApps.join(null));
    }

    /** 最多保留 MAX 个，多出的丢掉。 */
    @Test
    public void capsListSize() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < ExternalApps.MAX + 3; i++) many.add("com.example.app" + i);
        List<String> parsed = ExternalApps.parse(String.join(",", many));
        assertEquals(ExternalApps.MAX, parsed.size());
        assertEquals("com.example.app0", parsed.get(0));
    }

    /** 新增放在末尾；重复、非法或已满时不变。 */
    @Test
    public void addsToEnd() {
        List<String> one = ExternalApps.add(Collections.emptyList(), "com.spotify.music");
        assertEquals(Collections.singletonList("com.spotify.music"), one);
        List<String> two = ExternalApps.add(one, "org.videolan.vlc");
        assertEquals(Arrays.asList("com.spotify.music", "org.videolan.vlc"), two);
        assertEquals(two, ExternalApps.add(two, "com.spotify.music"));
        assertEquals(two, ExternalApps.add(two, "not valid"));
        List<String> full = new ArrayList<>();
        for (int i = 0; i < ExternalApps.MAX; i++) full.add("com.example.app" + i);
        assertEquals(full, ExternalApps.add(full, "com.spotify.music"));
    }

    /** 移除一项，其余顺序不变；不存在时不变。 */
    @Test
    public void removesEntry() {
        List<String> apps = Arrays.asList("com.spotify.music", "org.videolan.vlc", "com.example.radio");
        assertEquals(Arrays.asList("com.spotify.music", "com.example.radio"),
                ExternalApps.remove(apps, "org.videolan.vlc"));
        assertEquals(apps, ExternalApps.remove(apps, "com.missing.app"));
    }

    /** 返回的列表不能被调用方改动，配置快照保持不可变。 */
    @Test(expected = UnsupportedOperationException.class)
    public void parsedListIsUnmodifiable() {
        ExternalApps.parse("com.spotify.music").add("org.videolan.vlc");
    }
}
