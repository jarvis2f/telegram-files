package telegram.files.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

public final class SafePathResolver {

    private final List<Path> allowedRoots;

    public SafePathResolver(List<Path> allowedRoots) {
        if (allowedRoots == null || allowedRoots.isEmpty()) {
            throw new IllegalArgumentException("At least one allowed root is required");
        }
        this.allowedRoots = allowedRoots.stream().map(SafePathResolver::realDirectory).toList();
    }

    public Path requireAllowedRegularFile(Path candidate) {
        if (candidate == null || !candidate.isAbsolute()) {
            throw new IllegalArgumentException("File path must be absolute");
        }
        try {
            Path realPath = candidate.toRealPath();
            boolean allowed = allowedRoots.stream().anyMatch(realPath::startsWith);
            if (!allowed || !Files.isRegularFile(realPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("File is outside an allowed root");
            }
            return realPath;
        } catch (IOException exception) {
            throw new IllegalArgumentException("File cannot be resolved safely", exception);
        }
    }

    private static Path realDirectory(Path root) {
        try {
            Path realRoot = root.toRealPath();
            if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Allowed root is not a directory");
            }
            return realRoot;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Allowed root cannot be resolved", exception);
        }
    }
}
