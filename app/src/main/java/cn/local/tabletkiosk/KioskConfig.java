package cn.local.tabletkiosk;

import java.util.Objects;

/** 应用全部可配置参数的只读快照；默认值只用于首次启动。 */
public final class KioskConfig {
    /** 首次启动的占位主页，安装后在设置里改成自己的地址。 */
    public static final String DEFAULT_HOME_URL = "http://homeassistant.local:8123/";

    public final String homeUrl;
    public final int idleReloadHours;
    public final int retrySeconds;
    public final int watchBrightness;
    public final int standbyBrightness;
    public final int dimDelaySeconds;
    public final boolean motionEnabled;
    public final int motionSensitivity;
    public final int presenceHoldSeconds;
    public final boolean mqttEnabled;
    public final String mqttHost;
    public final int mqttPort;
    public final String mqttUsername;
    public final String mqttPassword;
    public final String mqttBaseTopic;
    public final String mqttDiscoveryPrefix;
    public final int mqttStateIntervalSeconds;
    public final boolean lanEnabled;
    public final int lanPort;
    public final String lanPassword;
    public final boolean lockTaskEnabled;

    /** 由构建器生成配置，空字符串统一转为非 null。 */
    private KioskConfig(Builder b) {
        homeUrl = nonNull(b.homeUrl);
        idleReloadHours = b.idleReloadHours;
        retrySeconds = b.retrySeconds;
        watchBrightness = b.watchBrightness;
        standbyBrightness = b.standbyBrightness;
        dimDelaySeconds = b.dimDelaySeconds;
        motionEnabled = b.motionEnabled;
        motionSensitivity = b.motionSensitivity;
        presenceHoldSeconds = b.presenceHoldSeconds;
        mqttEnabled = b.mqttEnabled;
        mqttHost = nonNull(b.mqttHost);
        mqttPort = b.mqttPort;
        mqttUsername = nonNull(b.mqttUsername);
        mqttPassword = nonNull(b.mqttPassword);
        mqttBaseTopic = nonNull(b.mqttBaseTopic);
        mqttDiscoveryPrefix = nonNull(b.mqttDiscoveryPrefix);
        mqttStateIntervalSeconds = b.mqttStateIntervalSeconds;
        lanEnabled = b.lanEnabled;
        lanPort = b.lanPort;
        lanPassword = nonNull(b.lanPassword);
        lockTaskEnabled = b.lockTaskEnabled;
    }

    /** 返回首次启动使用的默认配置。 */
    public static KioskConfig defaults() {
        return new Builder().build();
    }

    /** 以当前配置为基础创建构建器，便于只改其中几项。 */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.homeUrl = homeUrl;
        b.idleReloadHours = idleReloadHours;
        b.retrySeconds = retrySeconds;
        b.watchBrightness = watchBrightness;
        b.standbyBrightness = standbyBrightness;
        b.dimDelaySeconds = dimDelaySeconds;
        b.motionEnabled = motionEnabled;
        b.motionSensitivity = motionSensitivity;
        b.presenceHoldSeconds = presenceHoldSeconds;
        b.mqttEnabled = mqttEnabled;
        b.mqttHost = mqttHost;
        b.mqttPort = mqttPort;
        b.mqttUsername = mqttUsername;
        b.mqttPassword = mqttPassword;
        b.mqttBaseTopic = mqttBaseTopic;
        b.mqttDiscoveryPrefix = mqttDiscoveryPrefix;
        b.mqttStateIntervalSeconds = mqttStateIntervalSeconds;
        b.lanEnabled = lanEnabled;
        b.lanPort = lanPort;
        b.lanPassword = lanPassword;
        b.lockTaskEnabled = lockTaskEnabled;
        return b;
    }

    /** 判断 MQTT 连接相关参数是否变化，变化时才需要重连。 */
    public boolean sameMqttConnection(KioskConfig other) {
        return other != null && mqttEnabled == other.mqttEnabled && mqttPort == other.mqttPort
                && mqttHost.equals(other.mqttHost) && mqttUsername.equals(other.mqttUsername)
                && mqttPassword.equals(other.mqttPassword) && mqttBaseTopic.equals(other.mqttBaseTopic)
                && mqttDiscoveryPrefix.equals(other.mqttDiscoveryPrefix);
    }

    /** 判断局域网接口相关参数是否变化，变化时才需要重启监听。 */
    public boolean sameLanServer(KioskConfig other) {
        return other != null && lanEnabled == other.lanEnabled && lanPort == other.lanPort
                && lanPassword.equals(other.lanPassword);
    }

    /** 把 null 转成空字符串，避免调用方反复判空。 */
    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof KioskConfig)) return false;
        KioskConfig c = (KioskConfig) o;
        return idleReloadHours == c.idleReloadHours && retrySeconds == c.retrySeconds
                && watchBrightness == c.watchBrightness && standbyBrightness == c.standbyBrightness
                && dimDelaySeconds == c.dimDelaySeconds && motionEnabled == c.motionEnabled
                && motionSensitivity == c.motionSensitivity && presenceHoldSeconds == c.presenceHoldSeconds
                && mqttStateIntervalSeconds == c.mqttStateIntervalSeconds && lockTaskEnabled == c.lockTaskEnabled
                && homeUrl.equals(c.homeUrl) && sameMqttConnection(c) && sameLanServer(c);
    }

    @Override
    public int hashCode() {
        return Objects.hash(homeUrl, idleReloadHours, retrySeconds, watchBrightness, standbyBrightness,
                dimDelaySeconds, motionEnabled, motionSensitivity, presenceHoldSeconds, mqttEnabled, mqttHost,
                mqttPort, mqttUsername, mqttBaseTopic, mqttDiscoveryPrefix, mqttStateIntervalSeconds, lanEnabled,
                lanPort, lockTaskEnabled);
    }

    /** 可变构建器，字段初值即首次启动的默认值。 */
    public static final class Builder {
        public String homeUrl = DEFAULT_HOME_URL;
        public int idleReloadHours = 12;
        public int retrySeconds = 30;
        public int watchBrightness = 70;
        public int standbyBrightness = 20;
        public int dimDelaySeconds = 60;
        public boolean motionEnabled = true;
        public int motionSensitivity = 50;
        public int presenceHoldSeconds = 10;
        public boolean mqttEnabled = false;
        public String mqttHost = "";
        public int mqttPort = 1883;
        public String mqttUsername = "";
        public String mqttPassword = "";
        public String mqttBaseTopic = "home_kiosk";
        public String mqttDiscoveryPrefix = "homeassistant";
        public int mqttStateIntervalSeconds = 60;
        public boolean lanEnabled = false;
        public int lanPort = 2323;
        public String lanPassword = "";
        public boolean lockTaskEnabled = true;

        /** 生成不可变配置。 */
        public KioskConfig build() {
            return new KioskConfig(this);
        }
    }
}
