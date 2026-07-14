package telegram.files.security.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class SecurityTokenCodec {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecurityTokenCodec() {
    }

    public static String randomToken(int bytes) {
        if (bytes < 16) {
            throw new IllegalArgumentException("Security tokens require at least 128 bits");
        }
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static String digest(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static boolean matches(String rawToken, String expectedDigest) {
        if (rawToken == null || expectedDigest == null) {
            return false;
        }
        return MessageDigest.isEqual(
                digest(rawToken).getBytes(StandardCharsets.US_ASCII),
                expectedDigest.getBytes(StandardCharsets.US_ASCII)
        );
    }
}
