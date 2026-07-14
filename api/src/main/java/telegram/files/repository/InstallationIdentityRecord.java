package telegram.files.repository;

public record InstallationIdentityRecord(
        int identityVersion,
        String publicKey,
        String fingerprint,
        String privateKeyCiphertext,
        String peerSaltCiphertext,
        long createdAt,
        long updatedAt
) {
    public static final String SINGLETON_ID = "current";

    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS installation_identity
            (
                id                     VARCHAR(32) PRIMARY KEY,
                identity_version       INT NOT NULL,
                public_key             TEXT NOT NULL,
                fingerprint            VARCHAR(128) NOT NULL UNIQUE,
                private_key_ciphertext TEXT NOT NULL,
                peer_salt_ciphertext   TEXT NOT NULL,
                created_at             BIGINT NOT NULL,
                updated_at             BIGINT NOT NULL
            )
            """;

    public static final class InstallationIdentityRecordDefinition implements Definition {
        @Override
        public String getScheme() {
            return SCHEME;
        }
    }
}
