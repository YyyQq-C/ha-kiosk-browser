package cn.local.tabletkiosk;

/** 悬浮图标的位置换算，使用比例适应横竖屏和窗口尺寸变化。 */
final class FloatingPosition {
    /** 比例限制在可移动范围内，非法值回到右侧或底部。 */
    static float fraction(float value) {
        return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 1f;
    }

    /** 按父容器、图标和边距计算坐标，窗口太小时优先保持图标可见。 */
    static float coordinate(float fraction, int parentSize, int buttonSize, int margin) {
        float space = Math.max(0, parentSize - buttonSize);
        float inset = Math.min(margin, space / 2f);
        return inset + FloatingPosition.fraction(fraction) * Math.max(0f, space - 2f * inset);
    }

    /** 从拖动坐标换算比例，同时把超出屏幕的坐标限制到边界。 */
    static float relative(float coordinate, int parentSize, int buttonSize, int margin) {
        float space = Math.max(0, parentSize - buttonSize);
        float inset = Math.min(margin, space / 2f);
        float range = space - 2f * inset;
        return range > 0f ? fraction((coordinate - inset) / range) : 1f;
    }
}
