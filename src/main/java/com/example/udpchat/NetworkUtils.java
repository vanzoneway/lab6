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
            return nif.getName() + " - " + address.getHostAddress();
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
                continue;
            }
            for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                InetAddress addr = ia.getAddress();
                if (!(addr instanceof Inet4Address)) continue;
                short prefix = ia.getNetworkPrefixLength();
                String netmask = prefixLengthToNetmask(prefix);
                InetAddress bcast = ia.getBroadcast();
                if (bcast == null) {
                    try {
                        bcast = computeBroadcast((Inet4Address) addr, prefix);
                    } catch (UnknownHostException ignored) {
                    }
                }
                list.add(new IfaceInfo(nif, addr, netmask, bcast));
            }
        }
        return list;
    }

    public static String prefixLengthToNetmask(short prefix) {
        int value = 0xffffffff << (32 - prefix);
        byte[] bytes = new byte[] {
                (byte) (value >>> 24),
                (byte) (value >> 16 & 0xff),
                (byte) (value >> 8 & 0xff),
                (byte) (value & 0xff)
        };
        try {
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (UnknownHostException e) {
            return "0.0.0.0";
        }
    }

    public static InetAddress computeBroadcast(Inet4Address ip, short prefix) throws UnknownHostException {
        int ipInt = byteArrayToInt(ip.getAddress());
        int mask = 0xffffffff << (32 - prefix);
        int broadcast = (ipInt & mask) | (~mask);
        return InetAddress.getByName(intToIp(broadcast));
    }

    public static int byteArrayToInt(byte[] bytes) {
        int value = 0;
        for (byte b : bytes) {
            value = (value << 8) + (b & 0xFF);
        }
        return value;
    }

    public static String intToIp(int ip) {
        return ((ip >> 24) & 0xFF) + "." +
               ((ip >> 16) & 0xFF) + "." +
               ((ip >> 8) & 0xFF) + "." +
               (ip & 0xFF);
    }
}
