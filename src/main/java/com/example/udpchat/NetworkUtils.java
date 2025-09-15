// NetworkUtils.java
package com.example.udpchat;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class NetworkUtils {

    public static class IfaceInfo {
        public final NetworkInterface nif;
        public final InetAddress address;
        public final String netmask;
        public final InetAddress broadcast;

        public IfaceInfo(NetworkInterface nif, InetAddress address, String netmask, InetAddress broadcast) {
            this.nif = nif;
            this.address = address;
            this.netmask = netmask;
            this.broadcast = broadcast;
        }

        @Override
        public String toString() {
            return nif.getDisplayName() + " - " + address.getHostAddress();
        }
    }

    public static List<IfaceInfo> enumerateIPv4Interfaces() throws SocketException {
        List<IfaceInfo> list = new ArrayList<>();
        Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
        while (en.hasMoreElements()) {
            NetworkInterface nif = en.nextElement();
            try {
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
            } catch (SocketException ex) {
                // Ignore interfaces that cause exceptions on inspection
                continue;
            }
            for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                InetAddress addr = ia.getAddress();
                if (!(addr instanceof Inet4Address)) continue;
                list.add(new IfaceInfo(nif, addr,
                        prefixLengthToNetmask(ia.getNetworkPrefixLength()),
                        ia.getBroadcast()));
            }
        }
        return list;
    }

    private static String prefixLengthToNetmask(short prefix) {
        try {
            int value = 0xffffffff << (32 - prefix);
            byte[] bytes = new byte[]{
                    (byte) (value >>> 24),
                    (byte) (value >> 16 & 0xff),
                    (byte) (value >> 8 & 0xff),
                    (byte) (value & 0xff)
            };
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (UnknownHostException e) {
            e.printStackTrace();
            return "0.0.0.0";
        }
    }
}