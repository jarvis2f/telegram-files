package telegram.files.share;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

public final class TorrentViewStore {

    private final Vertx vertx;

    private final Path root;

    public TorrentViewStore(Vertx vertx, Path sharedRoot) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.root = Objects.requireNonNull(sharedRoot, "sharedRoot")
                .toAbsolutePath().normalize().resolve("torrent-views");
    }

    public Future<TorrentView> createSeedView(
            String infoHashV1,
            String fileName,
            Path stableContent,
            long expectedSize
    ) {
        return vertx.executeBlocking(() -> createSeedViewBlocking(
                infoHashV1, fileName, stableContent, expectedSize
        ), false);
    }

    public Future<TorrentView> prepareDownloadView(
            String infoHashV1,
            String fileName
    ) {
        return vertx.executeBlocking(() -> {
            TorrentView view = resolve(infoHashV1, fileName);
            Files.createDirectories(view.directory());
            rejectSymbolicPath(view.directory());
            if (Files.exists(view.content(), LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(view.content(), LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(view.content()))) {
                throw new IOException("Torrent download view contains an unsafe path");
            }
            return view;
        }, false);
    }

    private TorrentView createSeedViewBlocking(
            String infoHashV1,
            String fileName,
            Path rawContent,
            long expectedSize
    ) throws IOException {
        TorrentView view = resolve(infoHashV1, fileName);
        Path stableContent = Objects.requireNonNull(rawContent, "stableContent")
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(stableContent, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(stableContent) || Files.size(stableContent) != expectedSize) {
            throw new IOException("Stable Torrent content is invalid");
        }
        Files.createDirectories(view.directory());
        rejectSymbolicPath(view.directory());
        if (Files.exists(view.content(), LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(view.content(), LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(view.content()) || Files.size(view.content()) != expectedSize) {
                throw new IOException("Existing Torrent view is invalid");
            }
            if (!Files.isSameFile(view.content(), stableContent)) {
                throw new IOException(
                        "STORAGE_LAYOUT_UNSUPPORTED: Existing Torrent view is not linked to the source file"
                );
            }
            return view;
        }
        try {
            Files.createLink(view.content(), stableContent);
        } catch (UnsupportedOperationException | IOException linkFailure) {
            throw new IOException(
                    "STORAGE_LAYOUT_UNSUPPORTED: Torrent view requires a same-filesystem hard link",
                    linkFailure
            );
        }
        if (!Files.isRegularFile(view.content(), LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(view.content()) || Files.size(view.content()) != expectedSize) {
            throw new IOException("Torrent view did not stabilize");
        }
        if (!Files.isSameFile(view.content(), stableContent)) {
            throw new IOException("Torrent view does not share source file storage");
        }
        return view;
    }

    private TorrentView resolve(String infoHashV1, String fileName) {
        if (infoHashV1 == null || !infoHashV1.matches("[a-f0-9]{40}")) {
            throw new IllegalArgumentException("Torrent infoHash is invalid");
        }
        String safeName = V1TorrentService.safeFileName(fileName, infoHashV1);
        Path directory = root.resolve(infoHashV1).normalize();
        Path content = directory.resolve(safeName).normalize();
        if (!directory.startsWith(root) || !content.startsWith(directory)) {
            throw new IllegalArgumentException("Torrent view escaped SHARED_ROOT");
        }
        return new TorrentView(directory, content);
    }

    private static void rejectSymbolicPath(Path path) throws IOException {
        Path current = path;
        while (current != null) {
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Torrent view must not contain symbolic links");
            }
            current = current.getParent();
        }
    }

    public record TorrentView(Path directory, Path content) {
    }
}
