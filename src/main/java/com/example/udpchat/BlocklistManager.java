// BlocklistManager.java
package com.example.udpchat;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Manages a simple, in-memory blocklist of IP addresses.
 * This class is thread-safe.
 */
public class BlocklistManager {

    private final Set<String> blockedIpSet = Collections.synchronizedSet(new HashSet<>());

    /**
     * Checks if a given IP address is in the blocklist.
     *
     * @param ipAddress The IP address string to check.
     * @return true if the IP is blocked, false otherwise.
     */
    public boolean isIpBlocked(String ipAddress) {
        if (ipAddress == null) {
            return false;
        }
        return blockedIpSet.contains(ipAddress);
    }

    /**
     * Adds an IP address to the blocklist.
     *
     * @param ipAddress The IP address to block.
     */
    public void block(String ipAddress) {
        if (ipAddress != null && !ipAddress.isBlank()) {
            blockedIpSet.add(ipAddress);
        }
    }

    /**
     * Removes an IP address from the blocklist.
     *
     * @param ipAddress The IP address to unblock.
     */
    public void unblock(String ipAddress) {
        if (ipAddress != null) {
            blockedIpSet.remove(ipAddress);
        }
    }

    /**
     * Returns a copy of the current set of blocked IP addresses.
     *
     * @return A new Set containing all blocked IPs.
     */
    public Set<String> getBlockedIpSnapshot() {
        return new HashSet<>(blockedIpSet);
    }
}