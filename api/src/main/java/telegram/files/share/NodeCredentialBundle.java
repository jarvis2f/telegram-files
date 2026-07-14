package telegram.files.share;

import io.vertx.core.json.JsonObject;
import telegram.files.share.security.SecretEnvelope;
import telegram.files.share.security.SecretStore;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

public record NodeCredentialBundle(
        String accessToken,
        String refreshToken,
        long accessTokenExpiresAt,
        String trackerCredential
) {

    public NodeCredentialBundle(String accessToken, String refreshToken, long accessTokenExpiresAt) {
        this(accessToken, refreshToken, accessTokenExpiresAt, null);
    }

    public NodeCredentialBundle {
        if (accessToken == null || accessToken.length() < 32
            || refreshToken == null || refreshToken.length() < 32
            || accessTokenExpiresAt <= 0
            || (trackerCredential != null
                && !trackerCredential.matches("[A-Za-z0-9_-]{32,1024}"))) {
            throw new IllegalArgumentException("Node credential bundle is invalid");
        }
    }

    public String encrypt(SecretStore secretStore) {
        Objects.requireNonNull(secretStore, "secretStore");
        byte[] plaintext = new JsonObject()
                .put("accessToken", accessToken)
                .put("refreshToken", refreshToken)
                .put("accessTokenExpiresAt", accessTokenExpiresAt)
                .put("trackerCredential", trackerCredential)
                .encode()
                .getBytes(StandardCharsets.UTF_8);
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

    public static NodeCredentialBundle decrypt(String ciphertext, SecretStore secretStore) {
        Objects.requireNonNull(secretStore, "secretStore");
        JsonObject encoded = new JsonObject(ciphertext);
        SecretEnvelope envelope = new SecretEnvelope(
                encoded.getInteger("keyVersion", 0),
                encoded.getString("iv"),
                encoded.getString("ciphertext")
        );
        byte[] plaintext = secretStore.decrypt(envelope);
        try {
            JsonObject decoded = new JsonObject(new String(plaintext, StandardCharsets.UTF_8));
            return new NodeCredentialBundle(
                    decoded.getString("accessToken"),
                    decoded.getString("refreshToken"),
                    decoded.getLong("accessTokenExpiresAt", 0L),
                    decoded.getString("trackerCredential")
            );
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "NodeCredentialBundle[accessToken=[REDACTED], refreshToken=[REDACTED], "
               + "trackerCredential=[REDACTED], accessTokenExpiresAt=" + accessTokenExpiresAt + "]";
    }
}
