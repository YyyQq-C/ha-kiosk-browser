package cn.local.tabletkiosk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 版本比较和发布 JSON 解析不依赖网络。 */
public class AppUpdateTest {
    /** 只在远端版本更大时提示更新。 */
    @Test
    public void newerComparesNumericParts() {
        assertTrue(AppUpdate.newer("1.0.1", "1.0.2"));
        assertTrue(AppUpdate.newer("1.0.1", "v1.0.10"));
        assertFalse(AppUpdate.newer("1.0.1", "1.0.1"));
        assertFalse(AppUpdate.newer("1.0.1", "v1.0.0"));
        assertFalse(AppUpdate.newer("1.0.1", ""));
    }

    /** 从最新发布里取出标签和 apk 地址。 */
    @Test
    public void parseReadsTagAndApk() {
        String json = "{\"tag_name\":\"v1.0.2\",\"html_url\":\"https://github.com/YyyQq-C/ha-kiosk-browser/releases/tag/v1.0.2\","
                + "\"assets\":[{\"browser_download_url\":\"https://example.com/notes.txt\"},"
                + "{\"browser_download_url\":\"https://example.com/ha-kiosk-browser-1.0.2.apk\"}]}";
        AppUpdate.Release release = AppUpdate.parse(json);
        assertEquals("1.0.2", release.version);
        assertEquals("https://example.com/ha-kiosk-browser-1.0.2.apk", release.apkUrl);
        assertEquals("https://github.com/YyyQq-C/ha-kiosk-browser/releases/tag/v1.0.2", release.pageUrl);
    }
}
