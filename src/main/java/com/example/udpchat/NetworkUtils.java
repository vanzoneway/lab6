// NetworkUtils.java
package com.example.udpchat;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A utility class for network-related operations, like discovering network interfaces.
 */
public class NetworkUtils {

    /**
     * A record holding information about a specific network interface.
     */
    public record InterfaceInfo(NetworkInterface nif, InetAddress address, String netmask, InetAddress broadcast) {
        @Override
        public String toString() {
            return nif.getDisplayName() + " - " + address.getHostAddress();
        }
    }

    /**
     * Enumerates all active, non-loopback, non-virtual IPv4 network interfaces.
     *
     * @return A list of InterfaceInfo objects.
     * @throws SocketException if an I/O error occurs.
     */
    public static List<InterfaceInfo> getActiveIPv4Interfaces() throws SocketException {
        List<InterfaceInfo> interfaceList = new ArrayList<>();
        for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            try {
                if (nif.isUp() && !nif.isLoopback() && !nif.isVirtual()) {
                    for (InterfaceAddress ifaceAddr : nif.getInterfaceAddresses()) {
                        if (ifaceAddr.getAddress() instanceof Inet4Address) {
                            interfaceList.add(new InterfaceInfo(
                                    nif,
                                    ifaceAddr.getAddress(),
                                    convertPrefixLengthToNetmask(ifaceAddr.getNetworkPrefixLength()),
                                    ifaceAddr.getBroadcast()
                            ));
                        }
                    }
                }
            } catch (SocketException e) {
                // Ignore interfaces that cause errors during inspection
                System.err.println("Could not inspect network interface: " + nif.getDisplayName());
                e.printStackTrace();
            }
        }
        return interfaceList;
    }

    private static String convertPrefixLengthToNetmask(short prefixLength) {
        try {
            int netmaskInt = 0xFFFFFFFF << (32 - prefixLength);
            byte[] netmaskBytes = new byte[]{
                    (byte) (netmaskInt >>> 24),
                    (byte) (netmaskInt >> 16 & 0xFF),
                    (byte) (netmaskInt >> 8 & 0xFF),
                    (byte) (netmaskInt & 0xFF)
            };
            return InetAddress.getByAddress(netmaskBytes).getHostAddress();
        } catch (UnknownHostException e) {
            e.printStackTrace();
            return "0.0.0.0"; // Fallback
        }
    }
}