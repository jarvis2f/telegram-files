package telegram.files.share.security;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

public final class AesGcmSecretStore implements SecretStore {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] AAD = "telegram-files-secret-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private final Map<Integer, SecretKey> keys;
    private final int currentVersion;
    private final SecureRandom secureRandom;

    public AesGcmSecretStore(Map<Integer, SecretKey> keys, int currentVersion) {
        this(keys, currentVersion, new SecureRandom());
    }

    AesGcmSecretStore(Map<Integer, SecretKey> keys, int currentVersion, SecureRandom secureRandom) {
        this.keys = Map.copyOf(keys);
        this.currentVersion = currentVersion;
        this.secureRandom = secureRandom;
        SecretKey currentKey = this.keys.get(currentVersion);
        if (currentKey == null || currentKey.getEncoded().length != 32) {
            throw new IllegalArgumentException("Current AES-256 key is unavailable");
        }
    }

    @Override
    public SecretEnvelope encrypt(byte[] plaintext) {
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, keys.get(currentVersion), iv);
            byte[] ciphertext = cipher.doFinal(plaintext);
            return new SecretEnvelope(
                    currentVersion,
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(ciphertext)
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Secret encryption failed", exception);
        }
    }

    @Override
    public byte[] decrypt(SecretEnvelope envelope) {
        SecretKey key = keys.get(envelope.keyVersion());
        if (key == null) {
            throw new IllegalArgumentException("Secret key version is unavailable");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(envelope.ivBase64());
            if (iv.length != IV_BYTES) {
                throw new IllegalArgumentException("Secret IV is invalid");
            }
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, iv);
            return cipher.doFinal(Base64.getDecoder().decode(envelope.ciphertextBase64()));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Secret envelope is invalid or was tampered with", exception);
        }
    }

    private static Cipher cipher(int mode, SecretKey key, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(AAD);
        return cipher;
    }
}
