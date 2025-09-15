package com.example.udpchat;

import java.net.InetAddress;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class BlocklistManager {

    private final Set<String> blocked = Collections.synchronizedSet(new HashSet<>());

    public boolean isBlocked(InetAddress addr) {
        if (addr == null) return false;
        return blocked.contains(addr.getHostAddress());
    }

    public void block(String ip) {
        if (ip != null) blocked.add(ip);
    }

    public void unblock(String ip) {
        if (ip != null) blocked.remove(ip);
    }

    public Set<String> current() {
        return new HashSet<>(blocked);
    }
}
