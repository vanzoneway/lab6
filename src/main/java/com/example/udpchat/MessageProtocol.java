package com.example.udpchat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class MessageProtocol {

    public static final String TYPE_CHAT   = "CHAT";
    public static final String TYPE_HELLO  = "HELLO";
    public static final String TYPE_MBLOCK   = "MBLOCK";
    public static final String TYPE_MUNBLOCK = "MUNBLOCK";

    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", (int)c));
            }
        }
        return out.toString();
    }

    public static String unescape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i=0; i<s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i+2 < s.length()) {
                String hex = s.substring(i+1, i+3);
                int val = Integer.parseInt(hex, 16);
                out.append((char) val);
                i += 2;
            } else {
                out.append(c);
            }
        }
        return out.toString();
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
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static Parsed parse(byte[] data, int length) {
        String s = new String(data, 0, length, StandardCharsets.UTF_8);
        String[] parts = s.split("\\|");
        if (parts.length == 0) return null;
        String type = parts[0];
        Map<String,String> headers = new HashMap<>();
        String payload = "";
        for (int i=1; i<parts.length; i++) {
            String p = parts[i];
            int idx = p.indexOf('=');
            if (idx > 0) {
                String key = p.substring(0, idx);
                String val = unescape(p.substring(idx+1));
                headers.put(key, val);
            } else {
                payload = unescape(p);
            }
        }
        return new Parsed(type, headers, payload);
    }

    public static class Parsed {
        public final String type;
        public final Map<String,String> headers;
        public final String payload;

        public Parsed(String type, Map<String, String> headers, String payload) {
            this.type = type;
            this.headers = headers;
            this.payload = payload;
        }
    }
}
