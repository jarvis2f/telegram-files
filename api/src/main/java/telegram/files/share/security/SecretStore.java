package telegram.files.share.security;

public interface SecretStore {

    SecretEnvelope encrypt(byte[] plaintext);

    byte[] decrypt(SecretEnvelope envelope);
}
