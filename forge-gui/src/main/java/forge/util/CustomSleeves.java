package forge.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public final class CustomSleeves {
    public static final int MAX_DOWNLOAD_BYTES = 5 * 1024 * 1024;
    public static final int MAX_WIDTH = 1000;
    public static final int MAX_HEIGHT = 1400;
    public static final int CONNECT_TIMEOUT_MS = 10_000;
    public static final int READ_TIMEOUT_MS = 10_000;

    private CustomSleeves() {}

    public static String cacheFileName(final String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.append(".png").toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    // URL-safe Base64 without padding => no '=' to confuse the KEY=VALUE pref parser
    public static String encodeForPref(final String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(url.getBytes(StandardCharsets.UTF_8));
    }

    public static String decodeFromPref(final String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
    }

    // A slot's UI_SLEEVE_URLS entry stores the parked URL plus whether custom is the active
    // selection: "*" prefix means selected. base64url contains no '*', ',' or '=', so the
    // entry stays safe for the comma-joined, '='-parsed preference file.
    public static String encodeSlot(final String parkedUrl, final boolean selected) {
        final String enc = encodeForPref(parkedUrl);
        return selected && !enc.isEmpty() ? "*" + enc : enc;
    }

    public static String decodeSlotUrl(final String entry) {
        if (entry == null || entry.isEmpty()) {
            return "";
        }
        return decodeFromPref(entry.startsWith("*") ? entry.substring(1) : entry);
    }

    public static boolean isSlotSelected(final String entry) {
        return entry != null && entry.startsWith("*");
    }

    public static boolean withinDimensionLimit(final int w, final int h) {
        return w > 0 && h > 0 && w <= MAX_WIDTH && h <= MAX_HEIGHT;
    }

    public static boolean isHttps(final String url) {
        return url != null && url.regionMatches(true, 0, "https://", 0, 8);
    }
}
