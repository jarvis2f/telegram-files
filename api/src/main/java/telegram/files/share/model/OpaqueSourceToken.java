package telegram.files.share.model;

import io.vertx.core.json.JsonObject;
import telegram.files.share.security.SecretEnvelope;
import telegram.files.share.security.SecretStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

public final class OpaqueSourceToken {

    private static final int TOKEN_BYTES = 32;

    private OpaqueSourceToken() {
    }

    public static String issue(SecureRandom random) {
        Objects.requireNonNull(random, "random");
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    public static String digest(String token) {
        validate(token);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(StandardCharsets.US_ASCII))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static boolean matches(String token, String expectedDigest) {
        try {
            return MessageDigest.isEqual(
                    HexFormat.of().parseHex(digest(token)),
                    HexFormat.of().parseHex(expectedDigest)
            );
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public static String encrypt(String token, SecretStore secretStore) {
        validate(token);
        byte[] plaintext = token.getBytes(StandardCharsets.US_ASCII);
        try {
            SecretEnvelope envelope = secretStore.encrypt(plaintext);
            return new JsonObject()
                    .put("keyVersion", envelope.keyVersion())
                    .put("iv", envelope.ivBase64())
                    .put("ciphertext", envelope.ciphertextBase64())
                    .encode();
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public static String decrypt(String ciphertext, SecretStore secretStore) {
        JsonObject encoded = new JsonObject(ciphertext);
        byte[] plaintext = secretStore.decrypt(new SecretEnvelope(
                encoded.getInteger("keyVersion", 0),
                encoded.getString("iv"),
                encoded.getString("ciphertext")
        ));
        try {
            String token = new String(plaintext, StandardCharsets.US_ASCII);
            validate(token);
            return token;
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static void validate(String token) {
        if (token == null || token.length() < 22 || token.length() > 1024
            || !token.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Opaque source token is invalid");
        }
    }
}
