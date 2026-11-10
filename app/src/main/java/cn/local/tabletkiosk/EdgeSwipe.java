package cn.local.tabletkiosk;

/** 左边缘滑出设置、右边缘滑出应用栏的手势判定；只处理坐标，不依赖 Android，便于单测。 */
public final class EdgeSwipe {
    /** 一次手势结束后的判定结果。 */
    public enum Result {
        /** 不是边缘手势，或滑动未达到打开条件，手势被丢弃。 */
        NONE,
        /** 从左边缘向右滑够距离，打开设置。 */
        OPEN_LEFT,
        /** 从右边缘向左滑够距离，打开应用栏。 */
        OPEN_RIGHT,
        /** 几乎没有移动的轻点，应原样交给网页。 */
        TAP
    }

    private final float edgeWidth;
    private final float openDistance;
    private final float tapSlop;
    /** 视图宽度，右边缘按它计算；不大于 0 时不认右边缘。 */
    private float rightLimit;
    private boolean tracking;
    private boolean fromRight;
    private float downX;
    private float downY;
    private float maxMove;

    /**
     * @param edgeWidth 左边缘可起手的宽度，单位像素
     * @param openDistance 向右至少滑动的距离，单位像素
     * @param tapSlop 视为轻点的最大移动距离，单位像素
     */
    public EdgeSwipe(float edgeWidth, float openDistance, float tapSlop) {
        this.edgeWidth = edgeWidth;
        this.openDistance = openDistance;
        this.tapSlop = tapSlop;
    }

    /** 开启或关闭右边缘。width 是视图宽度，传 0 关闭。 */
    public void setRightEdge(float width) {
        rightLimit = width;
        if (width <= 0 && fromRight) tracking = false;
    }

    /** 手指按下；起点落在左边缘或已开启的右边缘窄条内时开始跟踪并返回 true。 */
    public boolean onDown(float x, float y) {
        boolean left = x >= 0 && x <= edgeWidth;
        boolean right = !left && rightLimit > 0 && x >= rightLimit - edgeWidth;
        tracking = left || right;
        fromRight = right;
        downX = x;
        downY = y;
        maxMove = 0;
        return tracking;
    }

    /** 手指移动，记录最大移动距离用于区分轻点。 */
    public void onMove(float x, float y) {
        if (!tracking) return;
        maxMove = Math.max(maxMove, (float) Math.hypot(x - downX, y - downY));
    }

    /** 手指抬起，返回判定结果并结束跟踪。 */
    public Result onUp(float x, float y) {
        if (!tracking) return Result.NONE;
        onMove(x, y);
        tracking = false;
        float dx = x - downX;
        float dy = y - downY;
        // 朝屏幕内侧滑够距离，并且横向位移大于纵向位移，避免上下滚动误开
        float inward = fromRight ? -dx : dx;
        if (inward >= openDistance && inward > Math.abs(dy)) return fromRight ? Result.OPEN_RIGHT : Result.OPEN_LEFT;
        if (maxMove <= tapSlop) return Result.TAP;
        return Result.NONE;
    }

    /** 手势被系统取消。 */
    public void cancel() {
        tracking = false;
    }

    /** 是否正在跟踪一次边缘手势。 */
    public boolean isTracking() {
        return tracking;
    }

    /** 按下点横坐标，用于把轻点还给网页。 */
    public float downX() {
        return downX;
    }

    /** 按下点纵坐标，用于把轻点还给网页。 */
    public float downY() {
        return downY;
    }
}
