package com.neracalab.backend.user;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Profile picture checks. The type is detected from the file content (magic bytes), never from the
 * name or the client's Content-Type, and only raster images are accepted (no SVG: it can carry scripts).
 */
final class AvatarImages {

    /** Largest accepted picture. */
    static final int MAX_BYTES = 2 * 1024 * 1024;

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF87 = "GIF87a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GIF89 = "GIF89a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RIFF = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WEBP = "WEBP".getBytes(StandardCharsets.US_ASCII);

    private AvatarImages() {
    }

    /** image/png, image/jpeg, image/gif or image/webp; null for anything else. */
    static String contentType(byte[] content) {
        if (startsWith(content, 0, PNG)) {
            return "image/png";
        }
        if (startsWith(content, 0, JPEG)) {
            return "image/jpeg";
        }
        if (startsWith(content, 0, GIF87) || startsWith(content, 0, GIF89)) {
            return "image/gif";
        }
        if (startsWith(content, 0, RIFF) && startsWith(content, 8, WEBP)) {
            return "image/webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] content, int offset, byte[] prefix) {
        return content.length >= offset + prefix.length
                && Arrays.equals(content, offset, offset + prefix.length, prefix, 0, prefix.length);
    }
}
