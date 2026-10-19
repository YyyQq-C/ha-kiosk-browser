package cn.local.tabletkiosk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 只扫描私有网段，并且只把 Home Assistant 的响应当成结果。 */
public class HaDiscoveryTest {
    /** 常见局域网地址能得到所在网段。 */
    @Test
    public void subnetUsesPrivatePrefixes() {
        assertEquals("192.168.1.", HaDiscovery.subnetPrefix("192.168.1.16"));
        assertEquals("10.0.0.", HaDiscovery.subnetPrefix("10.0.0.5"));
        assertEquals("172.16.5.", HaDiscovery.subnetPrefix("172.16.5.2"));
        assertNull(HaDiscovery.subnetPrefix("8.8.8.8"));
        assertNull(HaDiscovery.subnetPrefix("100.64.0.1"));
        assertFalse(HaDiscovery.privateIpv4(null));
    }

    /** 首页地址固定走 8123。 */
    @Test
    public void homeUrlUsesHaPort() {
        assertEquals("http://192.168.1.16:8123/", HaDiscovery.homeUrl("192.168.1.16"));
    }

    /** 只有带 Home Assistant 特征的正文才算找到。 */
    @Test
    public void responseMustLookLikeHa() {
        assertTrue(HaDiscovery.looksLikeHa("{\"message\":\"API running.\"}"));
        assertTrue(HaDiscovery.looksLikeHa("{\"name\":\"Home Assistant\"}"));
        assertFalse(HaDiscovery.looksLikeHa("{\"ok\":true}"));
        assertFalse(HaDiscovery.looksLikeHa(null));
    }
}
