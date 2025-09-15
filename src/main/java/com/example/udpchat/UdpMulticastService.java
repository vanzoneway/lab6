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

/**
 * Manages joining, leaving, sending, and receiving UDP multicast packets.
 */
public class UdpMulticastService {

    private final int port;
    private InetAddress currentGroup;
    private final NetworkUtils.InterfaceInfo networkInterface;
    private final UdpMessageListener messageListener;

    private MulticastSocket socket;
    private ExecutorService networkExecutor;
    private volatile boolean isJoined = false;
    private volatile boolean isHost = false;
    private int timeToLive = 1;

    public UdpMulticastService(int port, InetAddress initialGroup, NetworkUtils.InterfaceInfo networkInterface, UdpMessageListener messageListener) {
        this.port = port;
        this.currentGroup = initialGroup;
        this.networkInterface = networkInterface;
        this.messageListener = messageListener;
    }

    public synchronized void setHostStatus(boolean isHost) {
        this.isHost = isHost;
    }

    public synchronized void setTtl(int ttl) {
        this.timeToLive = Math.max(1, Math.min(ttl, 32));
        if (socket != null && !socket.isClosed()) {
            try {
                socket.setTimeToLive(this.timeToLive);
            } catch (IOException e) {
                System.err.println("Warning: Could not set TTL on multicast socket.");
                e.printStackTrace();
            }
        }
    }

    public synchronized boolean isJoined() {
        return isJoined;
    }

    public synchronized void joinOrSwitchGroup(InetAddress newGroup) throws IOException {
        if (newGroup == null) throw new IOException("Multicast group cannot be null.");
        if (isJoined && currentGroup != null && currentGroup.equals(newGroup)) return;
        if (isJoined) leaveGroup();
        this.currentGroup = newGroup;
        joinGroup();
    }

    private synchronized void joinGroup() throws IOException {
        if (isJoined) return;

        try {
            socket = new MulticastSocket(port);
            System.out.println("[DEBUG] MulticastSocket created successfully on port " + port);

            System.out.println("[DEBUG] -> Configuring: setReuseAddress(true)...");
            socket.setReuseAddress(true);

            System.out.println("[DEBUG] -> Configuring: setNetworkInterface for " + networkInterface.nif().getDisplayName() + "...");
            socket.setNetworkInterface(networkInterface.nif());

            System.out.println("[DEBUG] -> Configuring: setTimeToLive(" + timeToLive + ")...");
            socket.setTimeToLive(timeToLive);

            System.out.println("[DEBUG] -> Configuring: setLoopbackMode(false)...");
            socket.setLoopbackMode(false);

            System.out.println("[DEBUG] -> Joining group " + currentGroup + " via interface " + networkInterface.nif().getName() + "...");
            socket.joinGroup(new InetSocketAddress(currentGroup, port), networkInterface.nif());
            System.out.println("[DEBUG] -> SUCCESSFULLY joined group.");

        } catch (IOException e) {
            System.err.println("!!! ERROR during multicast socket setup or join.");
            e.printStackTrace();
            throw e; // Re-throw to notify the caller
        }

        networkExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "UDP-Multicast-Receiver-Thread");
            t.setDaemon(true);
            return t;
        });
        isJoined = true;
        networkExecutor.submit(this::listenForPackets);
    }

    public synchronized void leaveGroup() throws IOException {
        if (!isJoined) return;
        isJoined = false;
        try {
            if (socket != null) {
                socket.leaveGroup(new InetSocketAddress(currentGroup, port), networkInterface.nif());
                System.out.println("[DEBUG] Successfully left group " + currentGroup);
            }
        } catch (IOException e) {
            System.err.println("Error while trying to leave multicast group.");
            e.printStackTrace();
        } finally {
            if (socket != null && !socket.isClosed()) socket.close();
            if (networkExecutor != null) networkExecutor.shutdownNow();
            socket = null;
        }
    }

    private void listenForPackets() {
        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (isJoined && socket != null && !socket.isClosed()) {
            try {
                socket.receive(packet);
                if (messageListener != null) {
                    MessageProtocol.DecodedMessage decoded = MessageProtocol.decode(packet.getData(), packet.getLength());
                    if (decoded != null) {
                        messageListener.onMessageReceived(UdpTransport.MULTICAST, packet.getAddress(), decoded, currentGroup);
                    }
                }
            } catch (IOException e) {
                if (isJoined) {
                    System.err.println("Error receiving multicast packet.");
                    e.printStackTrace();
                }
            }
        }
    }

    public synchronized void send(String type, Map<String, String> headers, String payload) throws IOException {
        if (!isJoined || socket == null) throw new IOException("Not joined to a multicast group");
        if (isHost) headers.put("host", "1");
        headers.put("grp", currentGroup.getHostAddress());
        byte[] data = MessageProtocol.encode(type, headers, payload);
        DatagramPacket packet = new DatagramPacket(data, data.length, currentGroup, port);
        socket.send(packet);
    }
}