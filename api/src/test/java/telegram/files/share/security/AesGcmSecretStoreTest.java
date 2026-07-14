package telegram.files.share.security;

import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AesGcmSecretStoreTest {

    private final AesGcmSecretStore store = new AesGcmSecretStore(
            Map.of(1, new SecretKeySpec(new byte[32], "AES")),
            1
    );

    @Test
    void encryptsAndDecryptsWithoutExposingCiphertextInToString() {
        byte[] plaintext = "fixture-secret".getBytes(StandardCharsets.UTF_8);

        SecretEnvelope envelope = store.encrypt(plaintext);

        assertArrayEquals(plaintext, store.decrypt(envelope));
        assertFalse(envelope.toString().contains(envelope.ciphertextBase64()));
        assertFalse(envelope.toString().contains("fixture-secret"));
    }

    @Test
    void rejectsTamperedCiphertext() {
        SecretEnvelope envelope = store.encrypt("fixture-secret".getBytes(StandardCharsets.UTF_8));
        byte[] tampered = Base64.getDecoder().decode(envelope.ciphertextBase64());
        tampered[0] ^= 1;
        SecretEnvelope changed = new SecretEnvelope(
                envelope.keyVersion(),
                envelope.ivBase64(),
                Base64.getEncoder().encodeToString(tampered)
        );

        assertThrows(IllegalArgumentException.class, () -> store.decrypt(changed));
    }
}
