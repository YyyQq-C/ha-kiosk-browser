package cn.local.tabletkiosk;

/** 把系统电池广播中的数值换算成上报用的电量和充电状态。 */
public final class BatteryState {
    public static final String CHARGING = "charging";
    public static final String DISCHARGING = "discharging";
    public static final String NOT_CHARGING = "not_charging";
    public static final String FULL = "full";
    public static final String UNKNOWN = "unknown";

    /** 与 android.os.BatteryManager 中的状态值一致，单测时不依赖 Android 类。 */
    static final int STATUS_CHARGING = 2;
    static final int STATUS_DISCHARGING = 3;
    static final int STATUS_NOT_CHARGING = 4;
    static final int STATUS_FULL = 5;

    public final int percent;
    public final boolean charging;
    public final String state;

    /** 保存换算结果。 */
    private BatteryState(int percent, boolean charging, String state) {
        this.percent = percent;
        this.charging = charging;
        this.state = state;
    }

    /**
     * 根据广播字段换算。
     *
     * @param level 当前电量值，未知时为 -1
     * @param scale 电量满值，未知时为 -1
     * @param status 系统充电状态
     */
    public static BatteryState from(int level, int scale, int status) {
        int percent = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
        percent = Math.min(100, percent);
        String state;
        switch (status) {
            case STATUS_CHARGING:
                state = CHARGING;
                break;
            case STATUS_DISCHARGING:
                state = DISCHARGING;
                break;
            case STATUS_NOT_CHARGING:
                // 插着电但没在充，例如开启了充电上限保护
                state = NOT_CHARGING;
                break;
            case STATUS_FULL:
                state = FULL;
                break;
            default:
                state = UNKNOWN;
        }
        return new BatteryState(percent, CHARGING.equals(state), state);
    }
}
