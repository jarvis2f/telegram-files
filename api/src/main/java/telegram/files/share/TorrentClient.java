package telegram.files.share;

import io.vertx.core.Future;

import java.util.List;

public interface TorrentClient {

    Future<Void> healthCheck();

    Future<Void> add(AddRequest request);

    default Future<Void> addOrConfirm(AddRequest request, String infoHashV1) {
        return add(request).recover(addFailure -> get(infoHashV1)
                .compose(status -> status.privateTorrent() && !status.failed()
                        ? Future.<Void>succeededFuture()
                        : Future.<Void>failedFuture(addFailure))
                .recover(_ -> Future.<Void>failedFuture(addFailure)));
    }

    Future<TorrentStatus> get(String infoHashV1);

    default Future<List<PeerStatus>> getPeers(String infoHashV1) {
        return Future.succeededFuture(List.of());
    }

    Future<Void> pause(String infoHashV1);

    Future<Void> resume(String infoHashV1);

    Future<Void> recheck(String infoHashV1);

    Future<Void> delete(String infoHashV1);

    Future<Void> setUploadLimit(String infoHashV1, long bytesPerSecond);

    Future<Void> replaceTracker(String infoHashV1, String oldUrl, String newUrl);

    default Future<Void> replaceTrackerByBase(String infoHashV1, String trackerBaseUrl, String credential) {
        return Future.failedFuture(new UnsupportedOperationException("Tracker discovery is unavailable"));
    }

    record AddRequest(
            byte[] torrentBytes,
            String savePath,
            String category,
            List<String> tags,
            boolean stopped
    ) {
        public AddRequest {
            if (torrentBytes == null || torrentBytes.length == 0 || savePath == null
                || !savePath.startsWith("/") || category == null || category.isBlank()) {
                throw new IllegalArgumentException("Torrent add request is invalid");
            }
            torrentBytes = torrentBytes.clone();
            tags = tags == null ? List.of() : List.copyOf(tags);
        }

        @Override
        public byte[] torrentBytes() {
            return torrentBytes.clone();
        }
    }

    record TorrentStatus(
            String infoHashV1,
            String state,
            double progress,
            long downloadedBytes,
            long uploadedBytes,
            long downloadSpeedBytesPerSecond,
            long uploadSpeedBytesPerSecond,
            int connectedPeers,
            String savePath,
            boolean privateTorrent
    ) {
        public TorrentStatus {
            if (infoHashV1 == null || !infoHashV1.matches("[a-f0-9]{40}")
                || state == null || progress < 0 || progress > 1
                || downloadedBytes < 0 || uploadedBytes < 0
                || downloadSpeedBytesPerSecond < 0 || uploadSpeedBytesPerSecond < 0
                || connectedPeers < 0 || savePath == null || savePath.isBlank()) {
                throw new IllegalArgumentException("Torrent status is invalid");
            }
        }

        public boolean checking() {
            return state.toLowerCase(java.util.Locale.ROOT).contains("check");
        }

        public boolean failed() {
            String normalized = state.toLowerCase(java.util.Locale.ROOT);
            return normalized.contains("error") || normalized.contains("missing");
        }
    }

    record PeerStatus(String peerIdentity, long uploadedBytes) {
        public PeerStatus {
            if (peerIdentity == null || peerIdentity.isBlank() || uploadedBytes < 0) {
                throw new IllegalArgumentException("Torrent peer status is invalid");
            }
        }
    }
}
