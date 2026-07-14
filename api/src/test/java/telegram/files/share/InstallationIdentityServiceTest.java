package telegram.files.share;

import io.vertx.core.Future;
import org.junit.jupiter.api.Test;
import telegram.files.repository.InstallationIdentityRecord;
import telegram.files.repository.InstallationIdentityRepository;
import telegram.files.share.security.AesGcmSecretStore;
import telegram.files.share.security.SecretStore;

import javax.crypto.spec.SecretKeySpec;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InstallationIdentityServiceTest {
    @Test
    void persistsStableIdentitySignsCanonicalChallengeAndAnonymizesPeers() throws Exception {
        MemoryRepository repository = new MemoryRepository();
        InstallationIdentityService service = new InstallationIdentityService(
                repository,
                secretStore(),
                Clock.fixed(Instant.parse("2026-07-22T00:00:00Z"), ZoneOffset.UTC)
        );

        InstallationIdentityRecord first = service.loadOrCreate()
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        InstallationIdentityRecord second = service.loadOrCreate()
                .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(first.fingerprint(), second.fingerprint());
        assertTrue(first.fingerprint().matches("ed25519:v1:[A-Za-z0-9_-]{43}"));
        assertFalse(first.privateKeyCiphertext().contains(first.publicKey()));

        String signature = service.sign(
                first, "authorization-1", "challenge-1", "2026-07-22T00:10:00Z"
        );
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getUrlDecoder().decode(first.publicKey()))
        ));
        verifier.update(InstallationIdentityService.canonicalMessage(
                "authorization-1", "challenge-1", "2026-07-22T00:10:00Z"
        ));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(signature)));

        String peer = service.anonymizePeer(first, "a".repeat(40), "192.0.2.1:51413\0client");
        assertEquals(peer, service.anonymizePeer(
                second, "a".repeat(40), "192.0.2.1:51413\0client"
        ));
        assertNotEquals(peer, service.anonymizePeer(
                second, "a".repeat(40), "198.51.100.2:51413\0client"
        ));
        assertFalse(peer.contains("192.0.2.1"));
    }

    private static SecretStore secretStore() {
        return new AesGcmSecretStore(
                Map.of(1, new SecretKeySpec(new byte[32], "AES")),
                1
        );
    }

    private static final class MemoryRepository implements InstallationIdentityRepository {
        private InstallationIdentityRecord current;

        @Override
        public Future<InstallationIdentityRecord> getCurrent() {
            return Future.succeededFuture(current);
        }

        @Override
        public Future<InstallationIdentityRecord> saveIfAbsent(InstallationIdentityRecord identity) {
            if (current == null) current = identity;
            return Future.succeededFuture(current);
        }
    }
}
