package telegram.files.share;

import io.vertx.core.Future;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

public interface ContentHashService {

    Future<String> sha256(Path source);

    Future<String> sha256(Path source, LongConsumer progress, BooleanSupplier cancelled);
}
