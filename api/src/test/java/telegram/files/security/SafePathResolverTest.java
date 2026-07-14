package telegram.files.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SafePathResolverTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsRegularFilesInsideRootAndRejectsOutside() throws IOException {
        Path allowed = Files.createDirectory(temporaryDirectory.resolve("allowed"));
        Path inside = Files.writeString(allowed.resolve("inside.bin"), "fixture");
        Path outside = Files.writeString(temporaryDirectory.resolve("outside.bin"), "fixture");
        SafePathResolver resolver = new SafePathResolver(List.of(allowed));

        assertEquals(inside.toRealPath(), resolver.requireAllowedRegularFile(inside.toAbsolutePath()));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.requireAllowedRegularFile(outside.toAbsolutePath()));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.requireAllowedRegularFile(Path.of("relative.bin")));
    }

    @Test
    void rejectsSymbolicLinkEscapeWhenSupported() throws IOException {
        Path allowed = Files.createDirectory(temporaryDirectory.resolve("allowed"));
        Path outside = Files.writeString(temporaryDirectory.resolve("outside.bin"), "fixture");
        Path link = allowed.resolve("link.bin");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            return;
        }

        SafePathResolver resolver = new SafePathResolver(List.of(allowed));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.requireAllowedRegularFile(link.toAbsolutePath()));
    }
}
