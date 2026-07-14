package telegram.files.repository.impl;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import telegram.files.repository.InstallationIdentityRecord;
import telegram.files.repository.InstallationIdentityRepository;

import java.util.Objects;

public final class InstallationIdentityRepositoryImpl extends AbstractSqlRepository implements InstallationIdentityRepository {
    private final Pool pool;

    public InstallationIdentityRepositoryImpl(Pool pool) {
        super(pool);
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    @Override
    public Future<InstallationIdentityRecord> getCurrent() {
        return preparedQuery("SELECT * FROM installation_identity WHERE id = ?")
                .execute(Tuple.of(InstallationIdentityRecord.SINGLETON_ID))
                .map(rows -> rows.iterator().hasNext() ? map(rows.iterator().next()) : null);
    }

    @Override
    public Future<InstallationIdentityRecord> saveIfAbsent(InstallationIdentityRecord identity) {
        Objects.requireNonNull(identity, "identity");
        return pool.withTransaction(transaction -> preparedQuery(transaction, "SELECT * FROM installation_identity WHERE id = ?")
                .execute(Tuple.of(InstallationIdentityRecord.SINGLETON_ID))
                .compose(rows -> {
                    if (rows.iterator().hasNext()) {
                        return Future.succeededFuture(map(rows.iterator().next()));
                    }
                    return preparedQuery(transaction, """
                                    INSERT INTO installation_identity
                                    (id, identity_version, public_key, fingerprint,
                                     private_key_ciphertext, peer_salt_ciphertext,
                                     created_at, updated_at)
                                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                    """)
                            .execute(Tuple.of(
                                    InstallationIdentityRecord.SINGLETON_ID,
                                    identity.identityVersion(), identity.publicKey(),
                                    identity.fingerprint(), identity.privateKeyCiphertext(),
                                    identity.peerSaltCiphertext(), identity.createdAt(),
                                    identity.updatedAt()
                            ))
                            .map(identity);
                }));
    }

    private static InstallationIdentityRecord map(Row row) {
        return new InstallationIdentityRecord(
                ((Number) row.getValue("identity_version")).intValue(),
                row.getString("public_key"), row.getString("fingerprint"),
                row.getString("private_key_ciphertext"), row.getString("peer_salt_ciphertext"),
                ((Number) row.getValue("created_at")).longValue(),
                ((Number) row.getValue("updated_at")).longValue()
        );
    }
}

