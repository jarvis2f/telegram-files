package telegram.files.repository;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlUtilsTest {

    @Test
    void testSqlPlaceholderConversion() {
        String input = "DELETE FROM admin_bootstrap_token WHERE id = ?";
        // When Config.isPostgres() is false (default sqlite in tests), it should return input as-is
        String result = SqlUtils.sql(input);
        assertEquals(input, result);
    }
}
