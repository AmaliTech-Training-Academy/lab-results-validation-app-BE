package com.amalitech.labresultsvalidator.common.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes SHA-256 hex digests. Used to deduplicate uploaded files via the
 * {@code csv_uploads.file_sha256} column.
 */
public final class Sha256Util {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Sha256Util() {
    }

    /**
     * @param data the bytes to hash
     * @return the lowercase 64-character hex SHA-256 digest of {@code data}
     */
    public static String sha256Hex(byte[] data) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java platform, so this is unreachable.
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
        byte[] hash = digest.digest(data);
        char[] hex = new char[hash.length * 2];
        for (int i = 0; i < hash.length; i++) {
            int b = hash[i] & 0xFF;
            hex[i * 2] = HEX[b >>> 4];
            hex[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(hex);
    }

    /**
     * @param text the string to hash (UTF-8 encoded)
     * @return the lowercase 64-character hex SHA-256 digest of {@code text}
     */
    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }
}
