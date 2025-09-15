package com.example.udpchat;

public record ChatMessage(String author, String ip, String text, String timestamp, boolean isSelf) {
}
