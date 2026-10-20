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
    public final String ip;
    public final String appVersion;
    public final String model;
    public final String androidVersion;
    public final int volume;
    public final String wifiSsid;
    public final int wifiRssi;
    public final int cpuPercent;
    public final int memoryPercent;
    public final int memoryUsedMb;
    public final int memoryTotalMb;
    public final boolean memoryLow;
    public final int storagePercent;
    public final int appMemoryMb;
    public final int batteryTempTenths;

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
        ip = b.ip == null ? "" : b.ip;
        appVersion = b.appVersion == null ? "" : b.appVersion;
        model = b.model == null ? "" : b.model;
        androidVersion = b.androidVersion == null ? "" : b.androidVersion;
        volume = b.volume;
        wifiSsid = b.wifiSsid == null ? "" : b.wifiSsid;
        wifiRssi = b.wifiRssi;
        cpuPercent = b.cpuPercent;
        memoryPercent = b.memoryPercent;
        memoryUsedMb = b.memoryUsedMb;
        memoryTotalMb = b.memoryTotalMb;
        memoryLow = b.memoryLow;
        storagePercent = b.storagePercent;
        appMemoryMb = b.appMemoryMb;
        batteryTempTenths = b.batteryTempTenths;
    }

    /** 启动时的初始状态：电量未知、未连接。 */
    public static KioskStatus initial() {
        return new Builder().build();
    }

    /** 亮屏：系统屏幕是否点亮。待机变暗和屏保仍算亮着，只有真正息屏才是否。 */
    public boolean screenOn() {
        return interactive;
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
        b.ip = ip;
        b.appVersion = appVersion;
        b.model = model;
        b.androidVersion = androidVersion;
        b.volume = volume;
        b.wifiSsid = wifiSsid;
        b.wifiRssi = wifiRssi;
        b.cpuPercent = cpuPercent;
        b.memoryPercent = memoryPercent;
        b.memoryUsedMb = memoryUsedMb;
        b.memoryTotalMb = memoryTotalMb;
        b.memoryLow = memoryLow;
        b.storagePercent = storagePercent;
        b.appMemoryMb = appMemoryMb;
        b.batteryTempTenths = batteryTempTenths;
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
        public String ip = "";
        public String appVersion = "";
        public String model = "";
        public String androidVersion = "";
        public int volume = 0;
        public String wifiSsid = "";
        public int wifiRssi = -127;
        public int cpuPercent = -1;
        public int memoryPercent = -1;
        public int memoryUsedMb = -1;
        public int memoryTotalMb = -1;
        public boolean memoryLow = false;
        public int storagePercent = -1;
        public int appMemoryMb = -1;
        public int batteryTempTenths = -1000;

        /** 生成不可变快照。 */
        public KioskStatus build() {
            return new KioskStatus(this);
        }
    }
}
