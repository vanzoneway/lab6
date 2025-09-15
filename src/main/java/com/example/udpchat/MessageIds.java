package com.example.udpchat;

import java.security.SecureRandom;

public class MessageIds {

    private static final SecureRandom RND = new SecureRandom();

    public static String next() {
        long t = System.currentTimeMillis();
        int r = RND.nextInt();
        return Long.toHexString(t) + "-" + Integer.toHexString(r);
    }
}
