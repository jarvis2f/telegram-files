package telegram.files.repository;

public record SeedNodeIdentityRecord(
        String platformUrl,
        String nodeId,
        String nodeName,
        String credentialCiphertext,
        long tokenExpireAt,
        Long lastHeartbeatAt,
        String bindingStatus,
        long createdAt,
        long updatedAt
) {

    public static final String SINGLETON_ID = "current";

    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS seed_node_identity
            (
                id                    VARCHAR(32) PRIMARY KEY,
                platform_url          VARCHAR(2048) NOT NULL,
                node_id               VARCHAR(128) NOT NULL,
                node_name             VARCHAR(128) NOT NULL,
                credential_ciphertext TEXT NOT NULL,
                token_expire_at       BIGINT NOT NULL,
                last_heartbeat_at     BIGINT,
                binding_status        VARCHAR(32) NOT NULL,
                created_at            BIGINT NOT NULL,
                updated_at            BIGINT NOT NULL
            )
            """;

    public static class SeedNodeIdentityRecordDefinition implements Definition {
        @Override
        public String getScheme() {
            return SCHEME;
        }
    }
}
