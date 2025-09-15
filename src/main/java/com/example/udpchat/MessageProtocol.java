// MessageProtocol.java
package com.example.udpchat;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class MessageProtocol {

    public static final String TYPE_CHAT   = "CHAT";
    public static final String TYPE_HELLO  = "HELLO";
    public static final String TYPE_MBLOCK   = "MBLOCK";
    public static final String TYPE_MUNBLOCK = "MUNBLOCK";

    private static String escape(String s) {
        if (s == null) return "";
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String unescape(String s) {
        if (s == null) return "";
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    public static byte[] build(String type, Map<String, String> headers, String payload) {
        StringBuilder sb = new StringBuilder();
        sb.append(type);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                sb.append('|').append(e.getKey()).append('=').append(escape(e.getValue()));
            }
        }
        if (payload != null && !payload.isEmpty()) {
            sb.append('|').append(escape(payload));
        }
        // IMPORTANT: Always convert the string to bytes using UTF-8 encoding
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static Parsed parse(byte[] data, int length) {
        try {
            // IMPORTANT: Always convert bytes to a string using UTF-8 encoding
            String s = new String(data, 0, length, StandardCharsets.UTF_8);
            String[] parts = s.split("\\|");
            if (parts.length == 0) return null;

            String type = parts[0];
            Map<String, String> headers = new HashMap<>();
            String payload = "";

            for (int i = 1; i < parts.length; i++) {
                String p = parts[i];
                int idx = p.indexOf('=');
                if (idx > 0) { // This is a header
                    String key = p.substring(0, idx);
                    String val = unescape(p.substring(idx + 1));
                    headers.put(key, val);
                } else { // This is the payload (can only be one)
                    payload = unescape(p);
                }
            }
            return new Parsed(type, headers, payload);
        } catch (Exception e) {
            System.err.println("Failed to parse message protocol.");
            e.printStackTrace();
            return null; // Return null if parsing fails
        }
    }

    public static class Parsed {
        public final String type;
        public final Map<String, String> headers;
        public final String payload;

        public Parsed(String type, Map<String, String> headers, String payload) {
            this.type = type;
            this.headers = headers;
            this.payload = payload;
        }
    }
}