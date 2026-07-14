package telegram.files.share;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeTaskPollerTest {

    @Test
    void retryabilityMatchesTheV1Contract() {
        assertTrue(NodeTaskPoller.retryable("SOURCE_UNAVAILABLE"));
        assertTrue(NodeTaskPoller.retryable("INTERNAL_RETRYABLE"));
        assertFalse(NodeTaskPoller.retryable("HASH_MISMATCH"));
        assertFalse(NodeTaskPoller.retryable("SOURCE_PERMISSION_DENIED"));
    }
}
