package com.github.guoyyit.codecoveragejava.util;

import com.github.guoyyit.codecoveragejava.model.ElementType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Ids {

    private Ids() {
    }

    public static String sha256Hex(String input) {
        byte[] digest = sha256Digest().digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    public static String shortHash(String input) {
        return sha256Hex(input).substring(0, 16);
    }

    public static String elementId(String sourcePath, ElementType type, int line, String branchTag) {
        String key = sourcePath + "|" + type + "|" + line + "|" + (branchTag == null ? "" : branchTag);
        return type.name().charAt(0) + "-" + shortHash(key);
    }

    static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }
}
