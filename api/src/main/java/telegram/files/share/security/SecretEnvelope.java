package telegram.files.share.security;

import java.util.Objects;

public record SecretEnvelope(int keyVersion, String ivBase64, String ciphertextBase64) {

    public SecretEnvelope {
        if (keyVersion < 1) {
            throw new IllegalArgumentException("Key version must be positive");
        }
        Objects.requireNonNull(ivBase64, "ivBase64");
        Objects.requireNonNull(ciphertextBase64, "ciphertextBase64");
    }

    @Override
    public String toString() {
        return "SecretEnvelope[keyVersion=" + keyVersion + ", ciphertext=[REDACTED]]";
    }
}
