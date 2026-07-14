package telegram.files.share;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import telegram.files.repository.InstallationIdentityRecord;
import telegram.files.repository.InstallationIdentityRepository;
import telegram.files.share.security.SecretStore;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Objects;

public final class InstallationIdentityService {
    public static final int IDENTITY_VERSION = 1;

    public static final String CANONICALIZATION_VERSION = "telegram-seed-device-authorization:v1";

    private final InstallationIdentityRepository repository;

    private final SecretStore secretStore;

    private final Clock clock;

    public InstallationIdentityService(
            InstallationIdentityRepository repository,
            SecretStore secretStore
    ) {
        this(repository, secretStore, Clock.systemUTC());
    }

    InstallationIdentityService(
            InstallationIdentityRepository repository,
            SecretStore secretStore,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.secretStore = Objects.requireNonNull(secretStore, "secretStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Future<InstallationIdentityRecord> loadOrCreate() {
        return repository.getCurrent().compose(current -> {
            if (current != null) {
                validate(current);
                return Future.succeededFuture(current);
            }
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
                var pair = generator.generateKeyPair();
                byte[] salt = new byte[32];
                new SecureRandom().nextBytes(salt);
                String publicKey = encode(pair.getPublic().getEncoded());
                long now = clock.millis();
                InstallationIdentityRecord generated = new InstallationIdentityRecord(
                        IDENTITY_VERSION,
                        publicKey,
                        fingerprint(pair.getPublic().getEncoded()),
                        encrypt(encode(pair.getPrivate().getEncoded())),
                        encrypt(encode(salt)),
                        now,
                        now
                );
                return repository.saveIfAbsent(generated).map(saved -> {
                    validate(saved);
                    return saved;
                });
            } catch (Exception exception) {
                return Future.failedFuture(new IllegalStateException(
                        "Installation identity could not be generated", exception
                ));
            }
        });
    }

    public String sign(
            InstallationIdentityRecord identity,
            String authorizationId,
            String challenge,
            String expiresAt
    ) {
        validate(identity);
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey(identity));
            signature.update(canonicalMessage(authorizationId, challenge, expiresAt));
            return encode(signature.sign());
        } catch (Exception exception) {
            throw new IllegalStateException("Installation challenge could not be signed", exception);
        }
    }

    public String anonymizePeer(
            InstallationIdentityRecord identity,
            String infoHashV1,
            String peerIdentity
    ) {
        validate(identity);
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(
                    decode(decrypt(identity.peerSaltCiphertext())), "HmacSHA256"
            ));
            return encode(hmac.doFinal(
                    (infoHashV1 + "\u0000" + peerIdentity).getBytes(StandardCharsets.UTF_8)
            ));
        } catch (Exception exception) {
            throw new IllegalStateException("Peer identity could not be anonymized", exception);
        }
    }

    public static byte[] canonicalMessage(
            String authorizationId,
            String challenge,
            String expiresAt
    ) {
        if (authorizationId == null || authorizationId.isBlank()
            || challenge == null || challenge.isBlank()
            || expiresAt == null || expiresAt.isBlank()) {
            throw new IllegalArgumentException("Challenge fields are required");
        }
        return (CANONICALIZATION_VERSION + "\n" + authorizationId + "\n" + challenge + "\n" + expiresAt)
                .getBytes(StandardCharsets.UTF_8);
    }

    private void validate(InstallationIdentityRecord identity) {
        try {
            if (identity.identityVersion() != IDENTITY_VERSION) {
                throw new IllegalStateException("Installation identity version is unsupported");
            }
            byte[] publicKey = decode(identity.publicKey());
            if (!MessageDigest.isEqual(
                    fingerprint(publicKey).getBytes(StandardCharsets.US_ASCII),
                    identity.fingerprint().getBytes(StandardCharsets.US_ASCII)
            )) {
                throw new IllegalStateException("Installation identity fingerprint is invalid");
            }
            privateKey(identity);
            if (decode(decrypt(identity.peerSaltCiphertext())).length != 32) {
                throw new IllegalStateException("Installation peer salt is invalid");
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Installation identity is damaged", exception);
        }
    }

    private PrivateKey privateKey(InstallationIdentityRecord identity) throws Exception {
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(
                decode(decrypt(identity.privateKeyCiphertext()))
        ));
    }

    private String encrypt(String value) {
        return new SecretJsonCodec(secretStore).encrypt(new JsonObject().put("value", value));
    }

    private String decrypt(String value) {
        String decoded = new SecretJsonCodec(secretStore).decrypt(value).getString("value");
        if (decoded == null) throw new IllegalStateException("Encrypted installation value is invalid");
        return decoded;
    }

    private static String fingerprint(byte[] publicKey) throws Exception {
        return "ed25519:v1:" + encode(MessageDigest.getInstance("SHA-256").digest(publicKey));
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
