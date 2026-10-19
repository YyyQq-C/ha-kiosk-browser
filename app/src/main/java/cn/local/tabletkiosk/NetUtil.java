package cn.local.tabletkiosk;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TransportInfo;
import android.net.wifi.WifiInfo;
import android.util.Log;
import java.net.Inet4Address;

/** 网络辅助方法。 */
final class NetUtil {
    private NetUtil() {
    }

    /** 当前是否有可用网络。不要求能上公网，局域网也能算连上。 */
    static boolean hasNetwork(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /** 返回当前网络的 IPv4 地址，用于在设置页提示局域网接口地址；取不到时返回空字符串。 */
    static String localIpv4(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return "";
        Network network = cm.getActiveNetwork();
        if (network == null) return "";
        LinkProperties props = cm.getLinkProperties(network);
        if (props == null) return "";
        for (LinkAddress address : props.getLinkAddresses()) {
            if (address.getAddress() instanceof Inet4Address) return address.getAddress().getHostAddress();
        }
        return "";
    }

    /** 当前 Wi-Fi 名称和信号。没有权限或不是 Wi-Fi 时名称为空，信号为 -127。 */
    static WifiSnapshot wifi(Context context) {
        WifiSnapshot unknown = new WifiSnapshot("", -127);
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            if (cm == null) return unknown;
            Network network = cm.getActiveNetwork();
            if (network == null) return unknown;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null) return unknown;
            TransportInfo info = caps.getTransportInfo();
            if (!(info instanceof WifiInfo)) return unknown;
            WifiInfo wifi = (WifiInfo) info;
            String ssid = wifi.getSSID();
            if (ssid == null || "<unknown ssid>".equals(ssid)) ssid = "";
            else if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            return new WifiSnapshot(ssid, wifi.getRssi());
        } catch (RuntimeException e) {
            Log.w(KioskApp.LOG, "Wi-Fi status unavailable", e);
            return unknown;
        }
    }

    /** 一次 Wi-Fi 读取结果。 */
    static final class WifiSnapshot {
        final String ssid;
        final int rssi;

        /** 保存名称和信号强度。 */
        WifiSnapshot(String ssid, int rssi) {
            this.ssid = ssid == null ? "" : ssid;
            this.rssi = rssi;
        }
    }
}
