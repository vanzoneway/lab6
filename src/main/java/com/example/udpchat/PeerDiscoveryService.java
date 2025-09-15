// PeerDiscoveryService.java
package com.example.udpchat;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A service that periodically sends and listens for HELLO messages
 * to discover other participants on the network.
 */
public class PeerDiscoveryService {

    public interface ModeSelector {
        boolean useBroadcast();
        boolean useMulticast();
        InetAddress currentMulticastGroup();
    }

    public interface PeerListener {
        void onPeerStatusChanged(String ip, boolean isOnline);
    }

    private final ScheduledExecutorService scheduler;
    private final UdpBroadcastService broadcastService;
    private final UdpMulticastService multicastService;
    private final Supplier<String> nicknameSupplier;
    private final int discoveryIntervalMillis;
    private final PeerListener peerListener;
    private final ModeSelector modeSelector;

    private final Map<String, Long> broadcastPeers = new ConcurrentHashMap<>();
    private final Map<String, Long> multicastPeers = new ConcurrentHashMap<>();

    public PeerDiscoveryService(UdpBroadcastService bcast, UdpMulticastService multi, Supplier<String> nicknameSupplier,
                                int intervalMillis, PeerListener peerListener, ModeSelector modeSelector) {
        this.broadcastService = bcast;
        this.multicastService = multi;
        this.nicknameSupplier = nicknameSupplier;
        this.discoveryIntervalMillis = intervalMillis;
        this.peerListener = peerListener;
        this.modeSelector = modeSelector;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Peer-Discovery-Thread");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        broadcastHello();
        scheduler.scheduleAtFixedRate(this::broadcastHello, discoveryIntervalMillis, discoveryIntervalMillis, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::checkForExpiredPeers, 1000, 1000, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void broadcastHello() {
        try {
            Map<String, String> headers = new HashMap<>();
            String currentNick = nicknameSupplier.get();
            if (currentNick != null && !currentNick.isBlank()) {
                headers.put("nick", currentNick);
            }
            headers.put("id", MessageIds.next());

            if (modeSelector.useBroadcast() && broadcastService != null) {
                try {
                    // --- UPDATED to use the new command name ---
                    broadcastService.send(MessageProtocol.CMD_ANNOUNCE_PRESENCE, headers, "");
                } catch (Exception e) {
                    System.err.println("PeerDiscovery: Failed to send broadcast HELLO.");
                    e.printStackTrace();
                }
            }

            if (modeSelector.useMulticast() && multicastService != null && multicastService.isJoined()) {
                InetAddress group = modeSelector.currentMulticastGroup();
                if (group != null) {
                    Map<String, String> mcHeaders = new HashMap<>(headers);
                    mcHeaders.put("grp", group.getHostAddress());
                    try {
                        // --- UPDATED to use the new command name ---
                        multicastService.send(MessageProtocol.CMD_ANNOUNCE_PRESENCE, mcHeaders, "");
                    } catch (Exception e) {
                        System.err.println("PeerDiscovery: Failed to send multicast HELLO.");
                        e.printStackTrace();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("PeerDiscovery: An unexpected error occurred in broadcastHello.");
            e.printStackTrace();
        }
    }

    public void recordPeerActivity(UdpTransport transport, InetAddress address) {
        String ip = address.getHostAddress();
        Map<String, Long> peerMap = (transport == UdpTransport.MULTICAST) ? multicastPeers : broadcastPeers;

        boolean isNewPeer = peerMap.putIfAbsent(ip, System.currentTimeMillis()) == null;
        if (isNewPeer && peerListener != null) {
            peerListener.onPeerStatusChanged(ip, true);
        } else {
            peerMap.put(ip, System.currentTimeMillis());
        }
    }

    private void checkForExpiredPeers() {
        long now = System.currentTimeMillis();
        long timeout = Math.max(10000, discoveryIntervalMillis * 5L);

        removeExpiredPeersFromMap(broadcastPeers, now, timeout);
        removeExpiredPeersFromMap(multicastPeers, now, timeout);
    }

    private void removeExpiredPeersFromMap(Map<String, Long> peerMap, long now, long timeout) {
        List<String> expiredPeers = new ArrayList<>();
        for (Map.Entry<String, Long> entry : peerMap.entrySet()) {
            if ((now - entry.getValue()) > timeout) {
                expiredPeers.add(entry.getKey());
            }
        }
        for (String ip : expiredPeers) {
            peerMap.remove(ip);
            if (peerListener != null) {
                peerListener.onPeerStatusChanged(ip, false);
            }
        }
    }

    public List<String> getAllPeersSnapshot() {
        HashSet<String> allIps = new HashSet<>();
        allIps.addAll(broadcastPeers.keySet());
        allIps.addAll(multicastPeers.keySet());
        ArrayList<String> sortedList = new ArrayList<>(allIps);
        Collections.sort(sortedList);
        return sortedList;
    }
}