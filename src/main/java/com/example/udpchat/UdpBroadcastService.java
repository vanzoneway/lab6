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

/**
 * Manages sending and receiving UDP broadcast packets.
 */
public class UdpBroadcastService {

    private final int port;
    private final NetworkUtils.InterfaceInfo networkInterface;
    private final UdpMessageListener messageListener;

    private DatagramSocket receiveSocket;
    private DatagramSocket sendSocket;
    private ExecutorService networkExecutor;
    private static final InetAddress LIMITED_BROADCAST_ADDRESS;

    static {
        InetAddress address = null;
        try {
            address = InetAddress.getByName("255.255.255.255");
        } catch (Exception e) {
            System.err.println("Could not resolve limited broadcast address 255.255.255.255");
            e.printStackTrace();
        }
        LIMITED_BROADCAST_ADDRESS = address;
    }

    public UdpBroadcastService(int port, NetworkUtils.InterfaceInfo networkInterface, UdpMessageListener messageListener) {
        this.port = port;
        this.networkInterface = networkInterface;
        this.messageListener = messageListener;
    }

    public void start() throws SocketException {
        receiveSocket = new DatagramSocket(null);
        receiveSocket.setReuseAddress(true);
        receiveSocket.bind(new InetSocketAddress(port));

        sendSocket = new DatagramSocket(null);
        sendSocket.setReuseAddress(true);
        sendSocket.setBroadcast(true);
        sendSocket.bind(new InetSocketAddress(networkInterface.address(), 0));

        networkExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "UDP-Broadcast-Receiver-Thread");
            t.setDaemon(true);
            return t;
        });
        networkExecutor.submit(this::listenForPackets);
    }

    private void listenForPackets() {
        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (receiveSocket != null && !receiveSocket.isClosed()) {
            try {
                receiveSocket.receive(packet);
                if (messageListener != null) {
                    MessageProtocol.DecodedMessage decoded = MessageProtocol.decode(packet.getData(), packet.getLength());
                    if (decoded != null) {
                        messageListener.onMessageReceived(UdpTransport.BROADCAST, packet.getAddress(), decoded, null);
                    }
                }
            } catch (IOException e) {
                if (!receiveSocket.isClosed()) {
                    System.err.println("Error receiving broadcast packet.");
                    e.printStackTrace();
                }
            }
        }
    }

    public void stop() {
        if (receiveSocket != null && !receiveSocket.isClosed()) receiveSocket.close();
        if (sendSocket != null && !sendSocket.isClosed()) sendSocket.close();
        if (networkExecutor != null) networkExecutor.shutdownNow();
    }

    public void send(String type, Map<String, String> headers, String payload) throws IOException {
        byte[] data = MessageProtocol.encode(type, headers, payload);

        if (networkInterface.broadcast() != null) {
            sendSocket.send(new DatagramPacket(data, data.length, networkInterface.broadcast(), port));
        }

        if (LIMITED_BROADCAST_ADDRESS != null && (networkInterface.broadcast() == null || !networkInterface.broadcast().equals(LIMITED_BROADCAST_ADDRESS))) {
            sendSocket.send(new DatagramPacket(data, data.length, LIMITED_BROADCAST_ADDRESS, port));
        }
    }
}