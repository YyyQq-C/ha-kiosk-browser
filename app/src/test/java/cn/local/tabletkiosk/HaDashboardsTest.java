package cn.local.tabletkiosk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** 仪表盘地址只取首页的协议和主机，再接 url_path。 */
public class HaDashboardsTest {
    /** 带路径的首页不会把路径再拼进仪表盘地址。 */
    @Test
    public void originDropsPagePath() {
        assertEquals("https://ha.example:8123", HaDashboards.originOf("https://ha.example:8123/home-portrait/home"));
        assertNull(HaDashboards.originOf("file:///local"));
        assertEquals("https://ha.example:8123/lovelace-tablet",
                HaDashboards.pageUrl("https://ha.example:8123", "lovelace-tablet"));
    }
}
