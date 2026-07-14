package telegram.files.repository;

import io.vertx.core.Future;

import java.util.List;

public interface TorrentUploadSessionRepository {
    Future<Summary> reconcile(
            String resourceId,
            String infoHashV1,
            List<PeerCounter> peers,
            long observedAt,
            long toleranceMillis
    );

    record PeerCounter(String peerKey, long uploadedBytes) {
        public PeerCounter {
            if (peerKey == null || peerKey.isBlank() || uploadedBytes < 0) {
                throw new IllegalArgumentException("Peer counter is invalid");
            }
        }
    }

    record Summary(
            int activeCount,
            int completedCount,
            List<TorrentUploadSessionRecord> sessions
    ) {
        public Summary {
            sessions = List.copyOf(sessions);
        }
    }
}
