// MessageProtocol.java
package com.example.udpchat;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Defines the simple text-based protocol for encoding and decoding messages.
 * Format: TYPE|key1=value1|key2=value2|encoded_payload
 */
public class MessageProtocol {

    // --- Message Type Constants ---
    public static final String CMD_POST_USER_MESSAGE     = "USER_MESSAGE";
    public static final String CMD_ANNOUNCE_PRESENCE     = "PEER_ANNOUNCE";
    public static final String CMD_GROUP_HOST_ADD_BAN    = "GROUP_BAN_ADD";
    public static final String CMD_GROUP_HOST_REMOVE_BAN = "GROUP_BAN_REMOVE";

    /**
     * Encodes a message into a byte array according to the protocol.
     *
     * @param type    The message type (e.g., "CHAT").
     * @param headers A map of key-value pairs for message metadata.
     * @param payload The main content of the message.
     * @return A byte array ready for network transmission.
     */
    public static byte[] encode(String type, Map<String, String> headers, String payload) {
        StringBuilder sb = new StringBuilder();
        sb.append(type);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                sb.append('|').append(entry.getKey()).append('=').append(urlEncode(entry.getValue()));
            }
        }
        if (payload != null && !payload.isEmpty()) {
            sb.append('|').append(urlEncode(payload));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Decodes a byte array from the network into a structured message object.
     *
     * @param data   The raw byte data received.
     * @param length The actual length of the data in the buffer.
     * @return A DecodedMessage object, or null if parsing fails.
     */
    public static DecodedMessage decode(byte[] data, int length) {
        try {
            String fullMessage = new String(data, 0, length, StandardCharsets.UTF_8);
            String[] parts = fullMessage.split("\\|");
            if (parts.length == 0) return null;

            String type = parts[0];
            Map<String, String> headers = new HashMap<>();
            String payload = "";

            for (int i = 1; i < parts.length; i++) {
                String part = parts[i];
                int separatorIndex = part.indexOf('=');
                if (separatorIndex > 0) { // This is a header
                    String key = part.substring(0, separatorIndex);
                    String value = urlDecode(part.substring(separatorIndex + 1));
                    headers.put(key, value);
                } else { // This is the payload
                    payload = urlDecode(part);
                }
            }
            return new DecodedMessage(type, headers, payload);
        } catch (Exception e) {
            System.err.println("Failed to decode message protocol.");
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Represents a successfully parsed message.
     */
    public static class DecodedMessage {
        public final String type;
        public final Map<String, String> headers;
        public final String payload;

        public DecodedMessage(String type, Map<String, String> headers, String payload) {
            this.type = type;
            this.headers = headers;
            this.payload = payload;
        }
    }

    private static String urlEncode(String value) {
        if (value == null) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String urlDecode(String value) {
        if (value == null) return "";
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}