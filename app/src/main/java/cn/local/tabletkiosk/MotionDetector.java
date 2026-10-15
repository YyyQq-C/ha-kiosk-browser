package cn.local.tabletkiosk;

/**
 * 基于亮度网格的画面变化判断。先扣除整幅画面的平均亮度变化，
 * 减少自动曝光和开关灯带来的误报，再统计明显变化的格子比例。
 */
public final class MotionDetector {
    /** 单个格子亮度差超过该值才算变化，取值 0 到 255。 */
    static final int CELL_THRESHOLD = 20;
    private static final double LOOSEST = 0.20;
    private static final double STRICTEST = 0.005;
    private int[] previous;
    private volatile int sensitivity;

    /** @param sensitivity 灵敏度 0 到 100，0 表示关闭 */
    public MotionDetector(int sensitivity) {
        setSensitivity(sensitivity);
    }

    /** 修改灵敏度。 */
    public void setSensitivity(int value) {
        sensitivity = Math.max(0, Math.min(100, value));
    }

    /** 丢弃上一帧，例如摄像头重新打开后。 */
    public void reset() {
        previous = null;
    }

    /** 灵敏度对应的变化格子比例阈值：1 最迟钝为 20%，100 最灵敏为 0.5%。 */
    static double changedFractionThreshold(int sensitivity) {
        int s = Math.max(1, Math.min(100, sensitivity));
        return LOOSEST - (s - 1) * ((LOOSEST - STRICTEST) / 99.0);
    }

    /**
     * 输入一帧亮度网格，返回是否检测到画面变化。
     *
     * @param cells 每个格子的平均亮度，取值 0 到 255，长度固定
     */
    public boolean process(int[] cells) {
        if (cells == null || cells.length == 0) return false;
        int[] prev = previous;
        previous = cells.clone();
        if (sensitivity <= 0 || prev == null || prev.length != cells.length) return false;

        // 计算整体亮度偏移，扣除后再比较每个格子
        long sum = 0;
        for (int i = 0; i < cells.length; i++) sum += cells[i] - prev[i];
        double shift = (double) sum / cells.length;
        int changed = 0;
        for (int i = 0; i < cells.length; i++) {
            if (Math.abs(cells[i] - prev[i] - shift) > CELL_THRESHOLD) changed++;
        }
        return (double) changed / cells.length >= changedFractionThreshold(sensitivity);
    }

    /**
     * 把 YUV 图像的 Y 平面缩成亮度网格。
     *
     * @param y Y 平面原始字节
     * @param width 图像宽度
     * @param height 图像高度
     * @param rowStride 每行字节数
     * @param pixelStride 相邻像素间隔字节数
     * @param cols 网格列数
     * @param rows 网格行数
     */
    public static int[] downsample(byte[] y, int width, int height, int rowStride, int pixelStride,
                                   int cols, int rows) {
        int[] cells = new int[cols * rows];
        int[] counts = new int[cols * rows];
        // 每个方向隔 2 像素取样，足够判断大面积变化，减少运算量
        for (int py = 0; py < height; py += 2) {
            int row = Math.min(rows - 1, py * rows / height);
            int base = py * rowStride;
            for (int px = 0; px < width; px += 2) {
                int index = base + px * pixelStride;
                if (index >= y.length) break;
                int col = Math.min(cols - 1, px * cols / width);
                int cell = row * cols + col;
                cells[cell] += y[index] & 0xFF;
                counts[cell]++;
            }
        }
        for (int i = 0; i < cells.length; i++) {
            if (counts[i] > 0) cells[i] /= counts[i];
        }
        return cells;
    }
}
