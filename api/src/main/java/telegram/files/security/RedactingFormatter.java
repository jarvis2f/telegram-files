package telegram.files.security;

import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

public final class RedactingFormatter extends Formatter {

    private final SimpleFormatter delegate = new SimpleFormatter();

    @Override
    public String format(LogRecord record) {
        return SensitiveDataRedactor.redact(delegate.format(record));
    }
}
