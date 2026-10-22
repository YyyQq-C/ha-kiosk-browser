package cn.local.tabletkiosk;

import org.junit.Test;
import static org.junit.Assert.*;

/** 验证网页背景上传在系统文档选择器中的实际格式过滤。 */
public class WebUploadTypesTest {
    /** 同时声明扩展名与 MIME 的背景图片应去重保留三个真实类型。 */
    @Test
    public void backgroundImageTypesAreNormalized() {
        String[] types = WebUploadTypes.normalize(new String[]{"image/jpeg", "image/png", "image/webp", ".jpg", ".jpeg", ".png", ".webp"});
        assertArrayEquals(new String[]{"image/jpeg", "image/png", "image/webp"}, types);
        assertEquals("image/*", WebUploadTypes.primary(types));
    }

    /** 容忍空输入和不同网页格式，但无效过滤条件不能传给系统。 */
    @Test
    public void unsupportedFiltersFallbackWithoutInvalidMimeTypes() {
        assertArrayEquals(new String[0], WebUploadTypes.normalize(null));
        assertArrayEquals(new String[]{"image/png", "application/pdf"}, WebUploadTypes.normalize(new String[]{null, " IMAGE/PNG, .PDF ", "not-a-type", ".unknown"}));
        assertEquals("*/*", WebUploadTypes.primary(new String[]{"image/png", "application/pdf"}));
        assertEquals("*/*", WebUploadTypes.primary(new String[0]));
        assertEquals("image/png", WebUploadTypes.primary(new String[]{"image/png"}));
        assertArrayEquals(new String[]{"*/*"}, WebUploadTypes.normalize(new String[]{"image/png", "*/*"}));
    }
}
