package com.example.udpchat;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UdpMulticastService {

    private final int port;
    private InetAddress group;
    private final NetworkUtils.IfaceInfo iface;
    private final UdpMessageListener listener;

    private MulticastSocket socket;
    private ExecutorService exec;
    private volatile boolean joined = false;
    private volatile boolean host = false;
    private int ttl = 1;

    public UdpMulticastService(int port, InetAddress group, NetworkUtils.IfaceInfo iface,
                               BlocklistManager ignored,
                               UdpMessageListener listener) {
        this.port = port;
        this.group = group;
        this.iface = iface;
        this.listener = listener;
    }

    public synchronized void configureHost(boolean isHost) {
        this.host = isHost;
    }

    public synchronized void setTtl(int ttl) {
        this.ttl = Math.max(1, Math.min(ttl, 32));
        if (socket != null) {
            try {
                socket.setTimeToLive(this.ttl);
            } catch (IOException ignored) {
            }
        }
    }

    public synchronized boolean isJoined() {
        return joined;
    }

    public synchronized InetAddress currentGroup() {
        return group;
    }

    public synchronized void switchGroup(InetAddress newGroup) throws IOException {
        if (newGroup == null) throw new IOException("Group is null");
        if (joined && group != null && group.equals(newGroup)) return;
        if (joined) leave();
        this.group = newGroup;
        join();
    }

    public synchronized void join() throws IOException {
        if (joined) return;

        // Создаем сокет
        try {
            socket = new MulticastSocket(port);
            System.out.println("[DEBUG] MulticastSocket успешно создан на порту " + port);
        } catch (IOException e) {
            System.err.println("!!! ОШИБКА: Не удалось создать MulticastSocket на порту " + port);
            e.printStackTrace();
            throw e;
        }

        // --- Каждая настройка будет в отдельном try-catch для детальной диагностики ---

        try {
            System.out.println("[DEBUG] -> Настройка: setReuseAddress(true)...");
            socket.setReuseAddress(true);
        } catch (IOException e) {
            System.err.println("!!! ОШИБКА на этапе setReuseAddress:");
            e.printStackTrace();
            throw e;
        }

        try {
            System.out.println("[DEBUG] -> Настройка: setNetworkInterface для " + iface.nif.getDisplayName() + "...");
            socket.setNetworkInterface(iface.nif);
        } catch (IOException e) {
            System.err.println("!!! ОШИБКА на этапе setNetworkInterface. Вероятно, проблема в выбранном сетевом интерфейсе!");
            e.printStackTrace();
            throw e;
        }

        try {
            System.out.println("[DEBUG] -> Настройка: setTimeToLive(" + ttl + ")...");
            socket.setTimeToLive(ttl);
        } catch (IOException e) {
            System.err.println("!!! ОШИБКА на этапе setTimeToLive:");
            e.printStackTrace();
            throw e;
        }

        try {
            System.out.println("[DEBUG] -> Настройка: setLoopbackMode(false)...");
            socket.setLoopbackMode(false);
        } catch (Exception e) {
            System.err.println("!!! ПРЕДУПРЕЖДЕНИЕ: Не удалось отключить LoopbackMode (не критично)");
            e.printStackTrace();
        }

        // Присоединение к группе
        try {
            System.out.println("[DEBUG] -> Присоединение к группе " + group + " через интерфейс " + iface.nif.getName() + "...");
            socket.joinGroup(new InetSocketAddress(group, port), iface.nif);
            System.out.println("[DEBUG] -> УСПЕШНО присоединились к группе.");
        } catch (IOException e) {
            System.err.println("!!! ОШИБКА на этапе joinGroup. Проверьте адрес группы и настройки брандмауэра.");
            e.printStackTrace();
            throw e;
        }

        exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "UDP-Multicast-Receiver");
            t.setDaemon(true);
            return t;
        });
        joined = true;
        exec.submit(this::recvLoop);
    }

    private void recvLoop() {
        byte[] buf = new byte[2048];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (joined && socket != null && !socket.isClosed()) {
            try {
                socket.receive(pkt);
                if (listener != null) {
                    MessageProtocol.Parsed parsed = MessageProtocol.parse(pkt.getData(), pkt.getLength());
                    if (parsed != null) listener.onMessage(UdpTransport.MULTICAST, pkt.getAddress(), parsed, group);
                }
            } catch (IOException e) {
                if (joined) e.printStackTrace();
            }
        }
    }

    public synchronized void leave() throws IOException {
        if (!joined) return;
        joined = false;
        try {
            socket.leaveGroup(new InetSocketAddress(group, port), iface.nif);
            System.out.println("[DEBUG] Успешно покинули группу " + group);
        } finally {
            if (socket != null && !socket.isClosed()) socket.close();
            if (exec != null) exec.shutdownNow();
            socket = null;
        }
    }

    public synchronized void send(String type, Map<String, String> headers, String payload) throws IOException {
        if (!joined || socket == null) throw new IOException("Not joined to multicast group");
        if (host) headers.put("host", "1");
        headers.put("grp", group.getHostAddress());
        byte[] data = MessageProtocol.build(type, headers, payload);
        DatagramPacket pkt = new DatagramPacket(data, data.length, group, port);
        socket.send(pkt);
    }
}