package com.evops.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.CRC32;

/**
 * 校验和工具：
 * <ul>
 *   <li>SHA-256：整份导入文件指纹，用于重传同一文件的幂等判定；</li>
 *   <li>CRC32：导入分片、地面站二进制解码分片的轻量分片校验和。</li>
 * </ul>
 */
public final class Checksums {

    private Checksums() {
    }

    public static String sha256Hex(String content) {
        return sha256Hex(content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 分片 CRC32，返回 8 位十六进制（与地面站客户端口径一致，大小写不敏感比较） */
    public static String crc32Hex(String content) {
        CRC32 crc = new CRC32();
        crc.update(content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
        return String.format("%08X", crc.getValue());
    }

    public static boolean matches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return expected.trim().equalsIgnoreCase(actual.trim());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
