package telegram.files.repository.impl;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import telegram.files.repository.TorrentUploadSessionRecord;
import telegram.files.repository.TorrentUploadSessionRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class TorrentUploadSessionRepositoryImpl extends AbstractSqlRepository implements TorrentUploadSessionRepository {
    private final Pool pool;

    public TorrentUploadSessionRepositoryImpl(Pool pool) {
        super(pool);
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    @Override
    public Future<Summary> reconcile(
            String resourceId,
            String infoHashV1,
            List<PeerCounter> peers,
            long observedAt,
            long toleranceMillis
    ) {
        if (resourceId == null || resourceId.isBlank()
            || infoHashV1 == null || !infoHashV1.matches("[a-f0-9]{40}")
            || peers == null || observedAt <= 0 || toleranceMillis < 1) {
            return Future.failedFuture(new IllegalArgumentException("Upload session sample is invalid"));
        }
        return pool.withTransaction(connection -> preparedQuery(connection, """
                SELECT * FROM torrent_upload_session
                WHERE resource_id = ? ORDER BY started_at, session_key
                """)
                .execute(Tuple.of(resourceId))
                .compose(rows -> {
                    List<TorrentUploadSessionRecord> records = new ArrayList<>();
                    rows.forEach(row -> records.add(map(row)));
                    return persistReconciliation(
                            connection, records, peers, resourceId, infoHashV1,
                            observedAt, toleranceMillis
                    );
                }));
    }

    private Future<Summary> persistReconciliation(
            SqlConnection connection,
            List<TorrentUploadSessionRecord> records,
            List<PeerCounter> peers,
            String resourceId,
            String infoHashV1,
            long now,
            long toleranceMillis
    ) {
        Map<String, TorrentUploadSessionRecord> active = new HashMap<>();
        records.stream().filter(record -> "ACTIVE".equals(record.status()))
                .forEach(record -> active.put(record.peerKey(), record));
        Map<String, PeerCounter> observed = new HashMap<>();
        peers.stream().filter(peer -> peer.uploadedBytes() > 0)
                .forEach(peer -> observed.put(peer.peerKey(), peer));
        List<TorrentUploadSessionRecord> changed = new ArrayList<>();
        for (PeerCounter peer : observed.values()) {
            TorrentUploadSessionRecord current = active.get(peer.peerKey());
            if (current == null) {
                int generation = (int) records.stream()
                        .filter(record -> record.peerKey().equals(peer.peerKey())).count();
                TorrentUploadSessionRecord created = new TorrentUploadSessionRecord(
                        sessionKey(peer.peerKey(), generation), peer.peerKey(), resourceId,
                        infoHashV1, now, now, null, peer.uploadedBytes(),
                        peer.uploadedBytes(), 0, "ACTIVE", now, now
                );
                records.add(created);
                changed.add(created);
                continue;
            }
            boolean reset = peer.uploadedBytes() < current.lastClientCounter();
            long delta = reset
                    ? peer.uploadedBytes()
                    : peer.uploadedBytes() - current.lastClientCounter();
            TorrentUploadSessionRecord updated = new TorrentUploadSessionRecord(
                    current.sessionKey(), current.peerKey(), current.resourceId(), current.infoHashV1(),
                    current.startedAt(), now, null, Math.addExact(current.uploadedBytes(), delta),
                    peer.uploadedBytes(), current.counterEpoch() + (reset ? 1 : 0), "ACTIVE",
                    current.createdAt(), now
            );
            records.set(records.indexOf(current), updated);
            changed.add(updated);
        }
        for (TorrentUploadSessionRecord current : List.copyOf(records)) {
            if ("ACTIVE".equals(current.status()) && !observed.containsKey(current.peerKey())
                && now - current.lastObservedAt() > toleranceMillis) {
                TorrentUploadSessionRecord ended = new TorrentUploadSessionRecord(
                        current.sessionKey(), current.peerKey(), current.resourceId(), current.infoHashV1(),
                        current.startedAt(), current.lastObservedAt(), now, current.uploadedBytes(),
                        current.lastClientCounter(), current.counterEpoch(), "ENDED",
                        current.createdAt(), now
                );
                records.set(records.indexOf(current), ended);
                changed.add(ended);
            }
        }
        Future<Void> writes = Future.succeededFuture();
        for (TorrentUploadSessionRecord record : changed) {
            writes = writes.compose(_ -> upsert(connection, record));
        }
        return writes.map(_ -> {
            List<TorrentUploadSessionRecord> sorted = records.stream()
                    .sorted(Comparator.comparingLong(TorrentUploadSessionRecord::startedAt))
                    .toList();
            int activeCount = (int) sorted.stream().filter(record -> "ACTIVE".equals(record.status())).count();
            int completedCount = (int) sorted.stream().filter(record -> "ENDED".equals(record.status())).count();
            return new Summary(activeCount, completedCount, sorted);
        });
    }

    private Future<Void> upsert(SqlConnection connection, TorrentUploadSessionRecord record) {
        return preparedQuery(connection, "SELECT session_key FROM torrent_upload_session WHERE session_key = ?")
                .execute(Tuple.of(record.sessionKey()))
                .compose(rows -> rows.iterator().hasNext()
                        ? preparedQuery(connection, """
                        UPDATE torrent_upload_session SET last_observed_at = ?, ended_at = ?,
                        uploaded_bytes = ?, last_client_counter = ?, counter_epoch = ?,
                        status = ?, updated_at = ? WHERE session_key = ?
                        """)
                        .execute(Tuple.of(
                                record.lastObservedAt(), record.endedAt(), record.uploadedBytes(),
                                record.lastClientCounter(), record.counterEpoch(), record.status(),
                                record.updatedAt(), record.sessionKey()
                        )).mapEmpty()
                        : preparedQuery(connection, """
                        INSERT INTO torrent_upload_session
                        (session_key, peer_key, resource_id, info_hash_v1, started_at,
                         last_observed_at, ended_at, uploaded_bytes, last_client_counter,
                         counter_epoch, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                        .execute(Tuple.of(
                                record.sessionKey(), record.peerKey(), record.resourceId(),
                                record.infoHashV1(), record.startedAt(), record.lastObservedAt(),
                                record.endedAt(), record.uploadedBytes(), record.lastClientCounter(),
                                record.counterEpoch(), record.status(), record.createdAt(), record.updatedAt()
                        )).mapEmpty());
    }

    private static TorrentUploadSessionRecord map(Row row) {
        Number endedAt = (Number) row.getValue("ended_at");
        return new TorrentUploadSessionRecord(
                row.getString("session_key"), row.getString("peer_key"), row.getString("resource_id"),
                row.getString("info_hash_v1"), number(row, "started_at"),
                number(row, "last_observed_at"), endedAt == null ? null : endedAt.longValue(),
                number(row, "uploaded_bytes"), number(row, "last_client_counter"),
                ((Number) row.getValue("counter_epoch")).intValue(), row.getString("status"),
                number(row, "created_at"), number(row, "updated_at")
        );
    }

    private static long number(Row row, String field) {
        return ((Number) row.getValue(field)).longValue();
    }

    private static String sessionKey(String peerKey, int generation) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(
                            (peerKey + ":" + generation).getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Upload session key could not be generated", exception);
        }
    }
}
