package cn.local.tabletkiosk;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import java.net.Inet4Address;

/** 网络辅助方法。 */
final class NetUtil {
    private NetUtil() {
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
}
