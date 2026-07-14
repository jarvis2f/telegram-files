package telegram.files.security;

import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

class SensitiveDataRedactorTest {

    @Test
    void redactsCredentialsPrivateLocatorsAndLocalPaths() {
        String input = "Authorization=Bearer-secret chatId=-10042 "
                + "api_hash=hash-secret path=/Users/example/private.bin";

        String redacted = SensitiveDataRedactor.redact(input);

        assertFalse(redacted.contains("Bearer-secret"));
        assertFalse(redacted.contains("-10042"));
        assertFalse(redacted.contains("hash-secret"));
        assertFalse(redacted.contains("/Users/example"));
        assertTrue(redacted.contains("[REDACTED]"));
        assertTrue(redacted.contains("[LOCAL_PATH]"));
    }

    @Test
    void formatterAlsoRedactsExceptionText() {
        LogRecord record = new LogRecord(Level.SEVERE, "token=secret-value");
        record.setThrown(new IllegalStateException("password=another-secret"));

        String output = new RedactingFormatter().format(record);

        assertFalse(output.contains("secret-value"));
        assertFalse(output.contains("another-secret"));
    }
}
