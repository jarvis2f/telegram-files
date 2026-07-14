package telegram.files.share;

import io.vertx.core.json.JsonObject;
import telegram.files.share.security.SecretEnvelope;
import telegram.files.share.security.SecretStore;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

final class SecretJsonCodec {

    private final SecretStore secretStore;

    SecretJsonCodec(SecretStore secretStore) {
        this.secretStore = Objects.requireNonNull(secretStore, "secretStore");
    }

    String encrypt(JsonObject value) {
        byte[] plaintext = value.encode().getBytes(StandardCharsets.UTF_8);
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

    JsonObject decrypt(String ciphertext) {
        JsonObject encoded = new JsonObject(ciphertext);
        byte[] plaintext = secretStore.decrypt(new SecretEnvelope(
                encoded.getInteger("keyVersion", 0),
                encoded.getString("iv"),
                encoded.getString("ciphertext")
        ));
        try {
            return new JsonObject(new String(plaintext, StandardCharsets.UTF_8));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }
}
