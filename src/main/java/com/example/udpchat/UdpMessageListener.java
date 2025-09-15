package com.example.udpchat;


import java.net.InetAddress;

public interface UdpMessageListener {
    void onMessage(UdpTransport transport, InetAddress from, MessageProtocol.Parsed msg, InetAddress group);
}
