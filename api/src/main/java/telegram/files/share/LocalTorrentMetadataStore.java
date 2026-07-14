package telegram.files.share;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Objects;

public final class LocalTorrentMetadataStore {

    private final Vertx vertx;

    private final Path sharedRoot;

    private final Path root;

    public LocalTorrentMetadataStore(Vertx vertx, Path sharedRoot) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.sharedRoot = Objects.requireNonNull(sharedRoot, "sharedRoot").toAbsolutePath().normalize();
        this.root = this.sharedRoot.resolve("torrents");
    }

    public Future<Path> save(String infoHashV1, byte[] canonicalBytes) {
        if (infoHashV1 == null || !infoHashV1.matches("[a-f0-9]{40}")
            || canonicalBytes == null || canonicalBytes.length == 0) {
            return Future.failedFuture(new IllegalArgumentException("Torrent metadata identity is invalid"));
        }
        byte[] copy = canonicalBytes.clone();
        return vertx.executeBlocking(() -> saveBlocking(infoHashV1, copy), false);
    }

    public String relative(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(sharedRoot)) {
            throw new IllegalArgumentException("Torrent metadata escaped SHARED_ROOT");
        }
        return sharedRoot.relativize(normalized).toString().replace('\\', '/');
    }

    public Future<byte[]> readRelative(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return Future.failedFuture(new IllegalArgumentException("Torrent metadata path is required"));
        }
        return vertx.executeBlocking(() -> {
            Path target = sharedRoot.resolve(relativePath).normalize();
            if (!target.startsWith(root) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(target)) {
                throw new IOException("Torrent metadata path is invalid");
            }
            return Files.readAllBytes(target);
        }, false);
    }

    private Path saveBlocking(String infoHashV1, byte[] canonicalBytes) throws IOException {
        Files.createDirectories(root);
        Path target = root.resolve(infoHashV1 + ".torrent").normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Torrent metadata path escaped SHARED_ROOT");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(target)
                || !Arrays.equals(Files.readAllBytes(target), canonicalBytes)) {
                throw new IOException("Stored Torrent metadata conflicts with its infoHash");
            }
            return target;
        }
        Files.write(target, canonicalBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        if (!target.toFile().setReadOnly()) {
            throw new IOException("Torrent metadata could not be made read-only");
        }
        return target;
    }
}
