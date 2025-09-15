// UdpMessageListener.java
package com.example.udpchat;

import java.net.InetAddress;

/**
 * A functional interface for listeners that process incoming UDP messages.
 */
@FunctionalInterface
public interface UdpMessageListener {

    /**
     * Called when a new message is received and successfully parsed.
     *
     * @param transport The transport type (BROADCAST or MULTICAST) on which the message arrived.
     * @param source    The IP address of the sender.
     * @param message   The parsed message object containing type, headers, and payload.
     * @param group     The multicast group address if the transport was MULTICAST, otherwise null.
     */
    void onMessageReceived(UdpTransport transport, InetAddress source, MessageProtocol.DecodedMessage message, InetAddress group);
}