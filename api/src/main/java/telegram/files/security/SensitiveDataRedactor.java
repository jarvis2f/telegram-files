package telegram.files.security;

import java.util.regex.Pattern;

public final class SensitiveDataRedactor {

    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(authorization|cookie|access[_-]?token|refresh[_-]?token|device[_-]?code|"
                    + "opaque[_-]?source[_-]?token|tracker[_-]?credential|token|password|api[_-]?hash)"
                    + "(\\s*[:=]\\s*)([^\\s,;}&]+)"
    );
    private static final Pattern PRIVATE_LOCATOR = Pattern.compile(
            "(?i)(chat[_-]?id|message[_-]?id)(\\s*[:=]\\s*)(-?[0-9]+)"
    );
    private static final Pattern LOCAL_PATH = Pattern.compile(
            "(?<![A-Za-z0-9])/(?:Users|home|private|var|tmp|app)(?:/[^\\s,;}]*)?"
    );

    private SensitiveDataRedactor() {
    }

    public static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String redacted = KEY_VALUE.matcher(value).replaceAll("$1$2[REDACTED]");
        redacted = PRIVATE_LOCATOR.matcher(redacted).replaceAll("$1$2[REDACTED]");
        return LOCAL_PATH.matcher(redacted).replaceAll("[LOCAL_PATH]");
    }
}
