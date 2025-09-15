// UdpMulticastService.java
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
        if (socket != null && !socket.isClosed()) {
            try {
                socket.setTimeToLive(this.ttl);
            } catch (IOException e) {
                System.err.println("Warning: Could not set TTL on multicast socket.");
                e.printStackTrace();
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
        if (newGroup == null) throw new IOException("Multicast group cannot be null.");
        if (joined && group != null && group.equals(newGroup)) return; // Already in the correct group
        if (joined) leave();
        this.group = newGroup;
        join();
    }

    public synchronized void join() throws IOException {
        if (joined) return;

        // Create the socket
        try {
            socket = new MulticastSocket(port);
            System.out.println("[DEBUG] MulticastSocket created successfully on port " + port);
        } catch (IOException e) {
            System.err.println("!!! ERROR: Failed to create MulticastSocket on port " + port);
            e.printStackTrace();
            throw e;
        }

        // --- Each setup step is in a separate try-catch for detailed diagnostics ---

        try {
            System.out.println("[DEBUG] -> Configuring: setReuseAddress(true)...");
            socket.setReuseAddress(true);
        } catch (IOException e) {
            System.err.println("!!! ERROR during setReuseAddress:");
            e.printStackTrace();
            throw e;
        }

        try {
            System.out.println("[DEBUG] -> Configuring: setNetworkInterface for " + iface.nif.getDisplayName() + "...");
            socket.setNetworkInterface(iface.nif);
        } catch (IOException e) {
            System.err.println("!!! ERROR during setNetworkInterface. This is likely due to the selected network interface!");
            e.printStackTrace();
            throw e;
        }

        try {
            System.out.println("[DEBUG] -> Configuring: setTimeToLive(" + ttl + ")...");
            socket.setTimeToLive(ttl);
        } catch (IOException e) {
            System.err.println("!!! ERROR during setTimeToLive:");
            e.printStackTrace();
            throw e;
        }

        try {
            System.out.println("[DEBUG] -> Configuring: setLoopbackMode(false)...");
            socket.setLoopbackMode(false);
        } catch (Exception e) {
            System.err.println("!!! WARNING: Failed to disable LoopbackMode (this is not critical)");
            e.printStackTrace();
        }

        // Join the multicast group
        try {
            System.out.println("[DEBUG] -> Joining group " + group + " via interface " + iface.nif.getName() + "...");
            socket.joinGroup(new InetSocketAddress(group, port), iface.nif);
            System.out.println("[DEBUG] -> SUCCESSFULLY joined group.");
        } catch (IOException e) {
            System.err.println("!!! ERROR during joinGroup. Check the group address and firewall settings.");
            e.printStackTrace();
            throw e;
        }

        // Start the listener thread
        exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "UDP-Multicast-Receiver-Thread");
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
                if (joined) {
                    System.err.println("Error receiving multicast packet.");
                    e.printStackTrace();
                }
            }
        }
    }

    public synchronized void leave() throws IOException {
        if (!joined) return;
        joined = false;
        try {
            if (socket != null) {
                socket.leaveGroup(new InetSocketAddress(group, port), iface.nif);
                System.out.println("[DEBUG] Successfully left group " + group);
            }
        } catch (IOException e) {
            System.err.println("Error while trying to leave multicast group.");
            e.printStackTrace();
        } finally {
            if (socket != null && !socket.isClosed()) socket.close();
            if (exec != null) exec.shutdownNow();
            socket = null;
        }
    }

    public synchronized void send(String type, Map<String, String> headers, String payload) throws IOException {
        if (!joined || socket == null) throw new IOException("Not joined to a multicast group");
        if (host) headers.put("host", "1");
        headers.put("grp", group.getHostAddress());
        byte[] data = MessageProtocol.build(type, headers, payload);
        DatagramPacket pkt = new DatagramPacket(data, data.length, group, port);
        socket.send(pkt);
    }
}