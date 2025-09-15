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
import java.util.function.Supplier; // <-- ИМПОРТ

public class PeerDiscoveryService {

    public interface ModeSelector {
        boolean useBroadcast();
        boolean useMulticast();
        InetAddress currentMulticastGroup();
    }

    public interface PeerListener {
        void onPeer(String ip, boolean added, UdpTransport transport, String groupAddr);
    }

    private final ScheduledExecutorService scheduler;
    private final UdpBroadcastService broadcastService;
    private final UdpMulticastService multicastService;
    // --- ИЗМЕНЕНИЕ 1: Тип поля изменен со String на Supplier<String> ---
    private final Supplier<String> nicknameSupplier;
    private final int periodMillis;
    private final PeerListener peerListener;
    private final ModeSelector modeSelector;

    private final Map<String, Long> seenBroadcast = new ConcurrentHashMap<>();
    private final Map<String, Long> seenMulticast = new ConcurrentHashMap<>();

    // --- ИЗМЕНЕНИЕ 2: Тип в конструкторе изменен ---
    public PeerDiscoveryService(UdpBroadcastService bcast, UdpMulticastService multi, Supplier<String> nicknameSupplier,
                                int periodMillis, PeerListener peerListener,
                                ModeSelector modeSelector) {
        this.broadcastService = bcast;
        this.multicastService = multi;
        this.nicknameSupplier = nicknameSupplier; // <-- Присваивание
        this.periodMillis = periodMillis;
        this.peerListener = peerListener;
        this.modeSelector = modeSelector;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Peer-Discovery");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        sendHelloByMode();
        scheduler.scheduleAtFixedRate(this::sendHelloByMode, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::reap, 1000, 1000, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void sendHelloByMode() {
        Map<String,String> h = new HashMap<>();
        // --- ИЗМЕНЕНИЕ 3: Получаем актуальный ник через .get() ---
        String currentNick = nicknameSupplier.get();
        if (currentNick != null && !currentNick.isBlank()) {
            h.put("nick", currentNick);
        }
        h.put("id", MessageIds.next());

        try {
            if (modeSelector == null || modeSelector.useBroadcast()) {
                if (broadcastService != null) {
                    try { broadcastService.send(MessageProtocol.TYPE_HELLO, h, ""); } catch (Exception ignored) {}
                }
            }
            if (modeSelector == null || modeSelector.useMulticast()) {
                if (multicastService != null && multicastService.isJoined()) {
                    InetAddress grp = modeSelector.currentMulticastGroup();
                    if (grp != null) {
                        Map<String, String> mcHeaders = new HashMap<>(h);
                        mcHeaders.put("grp", grp.getHostAddress());
                        multicastService.send(MessageProtocol.TYPE_HELLO, mcHeaders, "");
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public void seen(UdpTransport transport, InetAddress addr) {
        String ip = addr.getHostAddress();
        Map<String, Long> map = (transport == UdpTransport.MULTICAST) ? seenMulticast : seenBroadcast;
        boolean justAdded = map.putIfAbsent(ip, System.currentTimeMillis()) == null;
        if (justAdded && peerListener != null) {
            String grp = (transport == UdpTransport.MULTICAST && modeSelector != null && modeSelector.currentMulticastGroup() != null)
                    ? modeSelector.currentMulticastGroup().getHostAddress() : null;
            peerListener.onPeer(ip, true, transport, grp);
        } else {
            // Если не только что добавили, просто обновим время
            map.put(ip, System.currentTimeMillis());
        }
    }

    private void reap() {
        long now = System.currentTimeMillis();
        long timeout = Math.max(10000, periodMillis * 5L); // Уменьшил таймаут для быстрого отвала
        reapMap(seenBroadcast, now, timeout, UdpTransport.BROADCAST, null);
        String grp = (modeSelector != null && modeSelector.currentMulticastGroup() != null)
                ? modeSelector.currentMulticastGroup().getHostAddress() : null;
        reapMap(seenMulticast, now, timeout, UdpTransport.MULTICAST, grp);
    }

    private void reapMap(Map<String, Long> map, long now, long timeout, UdpTransport transport, String grp) {
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if ((now - e.getValue()) > timeout) {
                toRemove.add(e.getKey());
            }
        }
        for (String ip : toRemove) {
            map.remove(ip);
            if (peerListener != null) {
                peerListener.onPeer(ip, false, transport, grp);
            }
        }
    }

    public List<String> snapshotAllPeers() {
        HashSet<String> set = new HashSet<>();
        set.addAll(seenBroadcast.keySet());
        set.addAll(seenMulticast.keySet());
        ArrayList<String> list = new ArrayList<>(set);
        Collections.sort(list);
        return list;
    }
}