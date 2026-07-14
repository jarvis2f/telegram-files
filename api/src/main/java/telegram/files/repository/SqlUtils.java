package telegram.files.repository;

import telegram.files.Config;

public final class SqlUtils {

    private SqlUtils() {
    }

    /**
     * Converts parameter placeholders ('?') to PostgreSQL positional parameter placeholders ('$1', '$2', ...)
     * when connected to a PostgreSQL database.
     */
    public static String sql(String query) {
        if (query == null || !Config.isPostgres() || !query.contains("?")) {
            return query;
        }
        StringBuilder sb = new StringBuilder(query.length() + 16);
        int paramIdx = 1;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                sb.append(c);
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                sb.append(c);
            } else if (c == '?' && !inSingleQuote && !inDoubleQuote) {
                sb.append('$').append(paramIdx++);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
