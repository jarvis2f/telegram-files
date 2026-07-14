package telegram.files.security.auth;

import io.vertx.core.json.JsonObject;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

public final class Argon2idPasswordHasher {

    static final int MEMORY_KIB = 65_536;
    static final int ITERATIONS = 3;
    static final int PARALLELISM = 1;
    static final int SALT_BYTES = 16;
    static final int HASH_BYTES = 32;

    private final SecureRandom secureRandom;

    public Argon2idPasswordHasher() {
        this(new SecureRandom());
    }

    Argon2idPasswordHasher(SecureRandom secureRandom) {
        this.secureRandom = Objects.requireNonNull(secureRandom);
    }

    public PasswordHash hash(char[] password) {
        validatePassword(password);
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        byte[] hash = derive(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM);
        JsonObject parameters = JsonObject.of(
                "algorithm", "argon2id",
                "version", Argon2Parameters.ARGON2_VERSION_13,
                "memoryKiB", MEMORY_KIB,
                "iterations", ITERATIONS,
                "parallelism", PARALLELISM,
                "salt", Base64.getUrlEncoder().withoutPadding().encodeToString(salt)
        );
        return new PasswordHash(
                Base64.getUrlEncoder().withoutPadding().encodeToString(hash),
                parameters.encode()
        );
    }

    public boolean verify(char[] password, PasswordHash expected) {
        if (password == null || expected == null) {
            return false;
        }
        try {
            JsonObject parameters = new JsonObject(expected.parameters());
            if (!"argon2id".equals(parameters.getString("algorithm"))
                    || parameters.getInteger("version") != Argon2Parameters.ARGON2_VERSION_13) {
                return false;
            }
            int memoryKiB = parameters.getInteger("memoryKiB");
            int iterations = parameters.getInteger("iterations");
            int parallelism = parameters.getInteger("parallelism");
            if (memoryKiB < 8_192 || iterations < 1 || parallelism < 1) {
                return false;
            }
            byte[] salt = Base64.getUrlDecoder().decode(parameters.getString("salt"));
            byte[] actual = derive(password, salt, memoryKiB, iterations, parallelism);
            byte[] expectedBytes = Base64.getUrlDecoder().decode(expected.hash());
            return MessageDigest.isEqual(actual, expectedBytes);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int memoryKiB, int iterations, int parallelism) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memoryKiB)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] output = new byte[HASH_BYTES];
        generator.generateBytes(password, output);
        return output;
    }

    private static void validatePassword(char[] password) {
        if (password == null || password.length < 12 || password.length > 256) {
            throw new IllegalArgumentException("Password must contain between 12 and 256 characters");
        }
    }

    public record PasswordHash(String hash, String parameters) {
        public PasswordHash {
            Objects.requireNonNull(hash);
            Objects.requireNonNull(parameters);
        }

        @Override
        public String toString() {
            return "PasswordHash[REDACTED]";
        }
    }
}
