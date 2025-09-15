// UdpBroadcastService.java
package com.example.udpchat;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UdpBroadcastService {

    private final int port;
    private final NetworkUtils.IfaceInfo iface;
    private final UdpMessageListener listener;

    private DatagramSocket recvSocket;
    private DatagramSocket sendSocket;
    private ExecutorService exec;
    private static final InetAddress LIMITED_BROADCAST;

    static {
        InetAddress lb = null;
        try {
            lb = InetAddress.getByName("255.255.255.255");
        } catch (Exception e) {
            System.err.println("Could not resolve limited broadcast address 255.255.255.255");
            e.printStackTrace();
        }
        LIMITED_BROADCAST = lb;
    }

    public UdpBroadcastService(int port, NetworkUtils.IfaceInfo iface, BlocklistManager ignored, UdpMessageListener listener) {
        this.port = port;
        this.iface = iface;
        this.listener = listener;
    }

    public void start() throws SocketException {
        recvSocket = new DatagramSocket(null);
        recvSocket.setReuseAddress(true);
        recvSocket.bind(new InetSocketAddress(port));

        sendSocket = new DatagramSocket(null);
        sendSocket.setReuseAddress(true);
        sendSocket.setBroadcast(true);
        sendSocket.bind(new InetSocketAddress(iface.address, 0));

        exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "UDP-Broadcast-Receiver-Thread");
            t.setDaemon(true);
            return t;
        });
        exec.submit(this::recvLoop);
    }

    private void recvLoop() {
        byte[] buf = new byte[2048];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (recvSocket != null && !recvSocket.isClosed()) {
            try {
                recvSocket.receive(pkt);
                if (listener != null) {
                    MessageProtocol.Parsed parsed = MessageProtocol.parse(pkt.getData(), pkt.getLength());
                    if (parsed != null) listener.onMessage(UdpTransport.BROADCAST, pkt.getAddress(), parsed, null);
                }
            } catch (IOException e) {
                if (!recvSocket.isClosed()) {
                    System.err.println("Error receiving broadcast packet.");
                    e.printStackTrace();
                }
            }
        }
    }

    public void stop() {
        if (recvSocket != null && !recvSocket.isClosed()) recvSocket.close();
        if (sendSocket != null && !sendSocket.isClosed()) sendSocket.close();
        if (exec != null) exec.shutdownNow();
    }

    public void send(String type, Map<String, String> headers, String payload) throws IOException {
        byte[] data = MessageProtocol.build(type, headers, payload);

        // Send to the directed broadcast address for the interface
        if (iface.broadcast != null) {
            sendSocket.send(new DatagramPacket(data, data.length, iface.broadcast, port));
        }

        // Also send to the limited broadcast address as a fallback
        if (LIMITED_BROADCAST != null && (iface.broadcast == null || !iface.broadcast.equals(LIMITED_BROADCAST))) {
            sendSocket.send(new DatagramPacket(data, data.length, LIMITED_BROADCAST, port));
        }
    }
}