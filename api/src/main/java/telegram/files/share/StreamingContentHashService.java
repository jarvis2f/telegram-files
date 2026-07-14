package telegram.files.share;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

public final class StreamingContentHashService implements ContentHashService {

    private static final int BUFFER_SIZE = 1024 * 1024;

    private final Vertx vertx;

    public StreamingContentHashService(Vertx vertx) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
    }

    @Override
    public Future<String> sha256(Path source) {
        return sha256(source, _ -> {
        }, () -> false);
    }

    @Override
    public Future<String> sha256(Path source, LongConsumer progress, BooleanSupplier cancelled) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(cancelled, "cancelled");
        Path normalized = source.toAbsolutePath().normalize();
        return vertx.executeBlocking(() -> hashStableFile(normalized, progress, cancelled), false);
    }

    private static String hashStableFile(
            Path source,
            LongConsumer progress,
            BooleanSupplier cancelled
    ) throws IOException {
        BasicFileAttributes before = attributes(source);
        MessageDigest digest = sha256Digest();
        long completed = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(source)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("Content hashing was cancelled");
                }
                if (read == 0) {
                    continue;
                }
                digest.update(buffer, 0, read);
                completed = Math.addExact(completed, read);
                progress.accept(completed);
            }
        }
        BasicFileAttributes after = attributes(source);
        if (before.size() != after.size()
            || !before.lastModifiedTime().equals(after.lastModifiedTime())
            || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw new IOException("Content changed while it was being hashed");
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static BasicFileAttributes attributes(Path source) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                source,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (!attributes.isRegularFile() || Files.isSymbolicLink(source)) {
            throw new IOException("Content source must be a regular non-symbolic file");
        }
        return attributes;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
