package telegram.files.share.model;

import org.junit.jupiter.api.Test;
import telegram.files.share.security.AesGcmSecretStore;

import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpaqueSourceTokenTest {

    @Test
    void issuesEncryptsAndMatchesHighEntropyCapabilities() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        AesGcmSecretStore store = new AesGcmSecretStore(
                Map.of(1, new SecretKeySpec(key, "AES")),
                1
        );
        String token = OpaqueSourceToken.issue(new SecureRandom());
        String digest = OpaqueSourceToken.digest(token);
        String ciphertext = OpaqueSourceToken.encrypt(token, store);

        assertTrue(token.length() >= 43);
        assertEquals(64, digest.length());
        assertTrue(OpaqueSourceToken.matches(token, digest));
        assertFalse(OpaqueSourceToken.matches(token + "x", digest));
        assertEquals(token, OpaqueSourceToken.decrypt(ciphertext, store));
        assertFalse(ciphertext.contains(token));
        assertDoesNotThrow(() -> Base64.getUrlDecoder().decode(token));
    }
}
