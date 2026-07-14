package telegram.files;

import io.vertx.core.MultiMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HttpVerticleWebSocketTest {

    @Test
    void readsNamedCookieAcrossCookieHeaders() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                .add("Cookie", "theme=dark; tf=web-session")
                .add("Cookie", "tf_admin=admin-token; quoted=\"quoted-value\"");

        assertEquals("web-session", HttpVerticle.cookieValue(headers, "tf"));
        assertEquals("admin-token", HttpVerticle.cookieValue(headers, "tf_admin"));
        assertEquals("quoted-value", HttpVerticle.cookieValue(headers, "quoted"));
        assertNull(HttpVerticle.cookieValue(headers, "missing"));
    }

    @Test
    void readsAndDecodesHandshakeQueryParameter() {
        String query = "telegramId=7789851018&label=hello%20world&_r=3";

        assertEquals("7789851018", HttpVerticle.queryParameter(query, "telegramId"));
        assertEquals("hello world", HttpVerticle.queryParameter(query, "label"));
        assertNull(HttpVerticle.queryParameter(query, "missing"));
        assertNull(HttpVerticle.queryParameter(null, "telegramId"));
    }
}
