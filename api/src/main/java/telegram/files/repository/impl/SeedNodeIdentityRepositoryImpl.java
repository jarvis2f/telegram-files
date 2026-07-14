package telegram.files.repository.impl;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import telegram.files.repository.SeedNodeIdentityRecord;
import telegram.files.repository.SeedNodeIdentityRepository;

import java.util.Objects;

public final class SeedNodeIdentityRepositoryImpl extends AbstractSqlRepository
        implements SeedNodeIdentityRepository {

    private final Pool pool;

    public SeedNodeIdentityRepositoryImpl(Pool pool) {
        super(pool);
        this.pool = pool;
    }

    @Override
    public Future<SeedNodeIdentityRecord> getCurrent() {
        return preparedQuery("""
                        SELECT platform_url, node_id, node_name, credential_ciphertext,
                               token_expire_at, last_heartbeat_at, binding_status,
                               created_at, updated_at
                        FROM seed_node_identity
                        WHERE id = ?
                        """)
                .execute(Tuple.of(SeedNodeIdentityRecord.SINGLETON_ID))
                .map(rows -> rows.iterator().hasNext() ? map(rows.iterator().next()) : null);
    }

    @Override
    public Future<Void> save(SeedNodeIdentityRecord identity) {
        Objects.requireNonNull(identity, "identity");
        return getCurrent().compose(current -> current == null
                ? write("""
                        INSERT INTO seed_node_identity
                            (id, platform_url, node_id, node_name,
                             credential_ciphertext, token_expire_at,
                             last_heartbeat_at, binding_status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Tuple.of(
                        SeedNodeIdentityRecord.SINGLETON_ID,
                        identity.platformUrl(),
                        identity.nodeId(),
                        identity.nodeName(),
                        identity.credentialCiphertext(),
                        identity.tokenExpireAt(),
                        identity.lastHeartbeatAt(),
                        identity.bindingStatus(),
                        identity.createdAt(),
                        identity.updatedAt()
                ))
                : write("""
                        UPDATE seed_node_identity
                        SET platform_url = ?, node_id = ?, node_name = ?,
                            credential_ciphertext = ?, token_expire_at = ?,
                            last_heartbeat_at = ?, binding_status = ?, updated_at = ?
                        WHERE id = ?
                        """, Tuple.of(
                        identity.platformUrl(),
                        identity.nodeId(),
                        identity.nodeName(),
                        identity.credentialCiphertext(),
                        identity.tokenExpireAt(),
                        identity.lastHeartbeatAt(),
                        identity.bindingStatus(),
                        identity.updatedAt(),
                        SeedNodeIdentityRecord.SINGLETON_ID
                )));
    }

    @Override
    public Future<Void> updateHeartbeat(long receivedAt) {
        return write("""
                        UPDATE seed_node_identity
                        SET last_heartbeat_at = ?, updated_at = ?
                        WHERE id = ?
                        """, Tuple.of(
                receivedAt, receivedAt, SeedNodeIdentityRecord.SINGLETON_ID
        ));
    }

    @Override
    public Future<Void> updateNodeName(String nodeName, long updatedAt) {
        return write("""
                        UPDATE seed_node_identity
                        SET node_name = ?, updated_at = ?
                        WHERE id = ?
                        """, Tuple.of(nodeName, updatedAt, SeedNodeIdentityRecord.SINGLETON_ID));
    }

    @Override
    public Future<Void> clear() {
        return write(
                "DELETE FROM seed_node_identity WHERE id = ?",
                Tuple.of(SeedNodeIdentityRecord.SINGLETON_ID)
        );
    }

    private Future<Void> write(String sql, Tuple parameters) {
        return pool.withTransaction(transaction ->
                preparedQuery(transaction, sql).execute(parameters)).mapEmpty();
    }

    private static SeedNodeIdentityRecord map(Row row) {
        Number tokenExpireAt = (Number) row.getValue("token_expire_at");
        Number lastHeartbeatAt = (Number) row.getValue("last_heartbeat_at");
        Number createdAt = (Number) row.getValue("created_at");
        Number updatedAt = (Number) row.getValue("updated_at");
        return new SeedNodeIdentityRecord(
                row.getString("platform_url"),
                row.getString("node_id"),
                row.getString("node_name"),
                row.getString("credential_ciphertext"),
                tokenExpireAt.longValue(),
                lastHeartbeatAt == null ? null : lastHeartbeatAt.longValue(),
                row.getString("binding_status"),
                createdAt.longValue(),
                updatedAt.longValue()
        );
    }
}
