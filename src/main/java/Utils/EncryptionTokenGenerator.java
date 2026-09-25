package Utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Utility class that reproduces the “X-Encryption-Token” algorithm
 * used by KeyboardGPT (Swift implementation) in pure Java.
 * The token is:
 *   MD5( "GPT_KEYBOARD_IS_AWESOME|" + UTC_now("yyyy-MM-dd HH") )
 * It changes every UTC hour.
 */
public class EncryptionTokenGenerator {


    /**
     * Get the current X-Encryption-Token (synchronous).
     */
    public static String getEncryptionToken() {
        // 1. current time in UTC, truncated to hours
        ZonedDateTime utcNow = ZonedDateTime.now(ZoneOffset.UTC);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH");
        String dateStr = utcNow.format(fmt);

        // 2. base string
        String base = "GPT_KEYBOARD_IS_AWESOME|" + dateStr;
        // 3. MD5
        return md5Hex(base);
    }

    /**
     * MD5 helper – returns lowercase hex string.
     */
    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception ex) {
            throw new RuntimeException("Cannot compute MD5", ex);
        }
    }

}
