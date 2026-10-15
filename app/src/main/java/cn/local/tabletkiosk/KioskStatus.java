package cn.local.tabletkiosk;

/** 当前运行状态的只读快照，供 MQTT 和局域网接口在任意线程读取。 */
public final class KioskStatus {
    public final String url;
    public final int brightness;
    public final boolean watching;
    public final boolean interactive;
    public final boolean presence;
    public final int battery;
    public final boolean charging;
    public final String chargeState;
    public final boolean mqttConnected;
    public final boolean deviceOwner;
    public final boolean lockTask;

    /** 由构建器生成快照。 */
    private KioskStatus(Builder b) {
        url = b.url == null ? "" : b.url;
        brightness = b.brightness;
        watching = b.watching;
        interactive = b.interactive;
        presence = b.presence;
        battery = b.battery;
        charging = b.charging;
        chargeState = b.chargeState == null ? BatteryState.UNKNOWN : b.chargeState;
        mqttConnected = b.mqttConnected;
        deviceOwner = b.deviceOwner;
        lockTask = b.lockTask;
    }

    /** 启动时的初始状态：电量未知、未连接。 */
    public static KioskStatus initial() {
        return new Builder().build();
    }

    /** 亮屏：系统屏幕点亮并且处于观看亮度。 */
    public boolean screenOn() {
        return interactive && watching;
    }

    /** 复制当前快照用于修改其中几项。 */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.url = url;
        b.brightness = brightness;
        b.watching = watching;
        b.interactive = interactive;
        b.presence = presence;
        b.battery = battery;
        b.charging = charging;
        b.chargeState = chargeState;
        b.mqttConnected = mqttConnected;
        b.deviceOwner = deviceOwner;
        b.lockTask = lockTask;
        return b;
    }

    /** 可变构建器。 */
    public static final class Builder {
        public String url = "";
        public int brightness = 0;
        public boolean watching = true;
        public boolean interactive = true;
        public boolean presence = false;
        public int battery = -1;
        public boolean charging = false;
        public String chargeState = BatteryState.UNKNOWN;
        public boolean mqttConnected = false;
        public boolean deviceOwner = false;
        public boolean lockTask = false;

        /** 生成不可变快照。 */
        public KioskStatus build() {
            return new KioskStatus(this);
        }
    }
}
