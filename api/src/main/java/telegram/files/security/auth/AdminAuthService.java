package telegram.files.security.auth;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.sqlclient.*;
import telegram.files.security.SensitiveDataRedactor;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import static telegram.files.repository.SqlUtils.sql;
import static telegram.files.security.auth.AdminAuthModels.*;

public final class AdminAuthService {

    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,63}");

    private static final String ACTIVE = "ACTIVE";

    private static final String BOOTSTRAP_ID = "current";

    private final Vertx vertx;

    private final Pool pool;

    private final Clock clock;

    private final Argon2idPasswordHasher passwordHasher;

    private final Duration bootstrapLifetime;

    private final Duration idleLifetime;

    private final Duration absoluteLifetime;

    public AdminAuthService(Vertx vertx, Pool pool) {
        this(
                vertx,
                pool,
                Clock.systemUTC(),
                new Argon2idPasswordHasher(),
                Duration.ofMinutes(15),
                Duration.ofHours(12),
                Duration.ofDays(7)
        );
    }

    AdminAuthService(
            Vertx vertx,
            Pool pool,
            Clock clock,
            Argon2idPasswordHasher passwordHasher,
            Duration bootstrapLifetime,
            Duration idleLifetime,
            Duration absoluteLifetime
    ) {
        this.vertx = Objects.requireNonNull(vertx);
        this.pool = Objects.requireNonNull(pool);
        this.clock = Objects.requireNonNull(clock);
        this.passwordHasher = Objects.requireNonNull(passwordHasher);
        this.bootstrapLifetime = requirePositive(bootstrapLifetime, "bootstrapLifetime");
        this.idleLifetime = requirePositive(idleLifetime, "idleLifetime");
        this.absoluteLifetime = requirePositive(absoluteLifetime, "absoluteLifetime");
    }

    public Future<BootstrapState> initialize() {
        return adminCount().compose(count -> {
            if (count > 0) {
                return pool.preparedQuery(sql("DELETE FROM admin_bootstrap_token WHERE id = ?"))
                        .execute(Tuple.of(BOOTSTRAP_ID))
                        .map(new BootstrapState(false, null, 0));
            }
            String rawToken = SecurityTokenCodec.randomToken(32);
            long now = clock.millis();
            long expiresAt = now + bootstrapLifetime.toMillis();
            return pool.withTransaction(connection ->
                            connection.preparedQuery(sql("DELETE FROM admin_bootstrap_token WHERE id = ?"))
                                    .execute(Tuple.of(BOOTSTRAP_ID))
                                    .compose(_ -> connection.preparedQuery(sql("""
                                                    INSERT INTO admin_bootstrap_token
                                                        (id, token_digest, expires_at, consumed_at, created_at)
                                                    VALUES (?, ?, ?, NULL, ?)
                                                    """))
                                            .execute(Tuple.of(
                                                    BOOTSTRAP_ID,
                                                    SecurityTokenCodec.digest(rawToken),
                                                    expiresAt,
                                                    now
                                            )))
                    )
                    .map(new BootstrapState(true, rawToken, expiresAt));
        });
    }

    public Future<Boolean> bootstrapRequired() {
        return adminCount().map(count -> count == 0);
    }

    public Future<IssuedSession> bootstrap(
            String rawBootstrapToken,
            String requestedUsername,
            char[] requestedPassword,
            boolean localNetworkRequest
    ) {
        if (!localNetworkRequest) {
            return Future.failedFuture(new AuthException(
                    403, "BOOTSTRAP_LOCAL_NETWORK_REQUIRED",
                    "Bootstrap is only available from loopback or a private LAN address"
            ));
        }
        String username;
        try {
            username = normalizeUsername(requestedUsername);
        } catch (AuthException exception) {
            return Future.failedFuture(exception);
        }
        char[] password = copyPassword(requestedPassword);
        return hashPassword(password)
                .compose(passwordHash -> createInitialAdmin(
                        rawBootstrapToken, username, passwordHash
                ))
                .compose(account -> issueSession(account.id(), account.username(), account.sessionVersion()))
                .eventually(() -> {
                    Arrays.fill(password, '\0');
                    return Future.succeededFuture();
                });
    }

    public Future<IssuedSession> login(String requestedUsername, char[] requestedPassword, String source) {
        String username;
        try {
            username = normalizeUsername(requestedUsername);
        } catch (AuthException exception) {
            return Future.failedFuture(invalidCredentials());
        }
        char[] password = copyPassword(requestedPassword);
        return findAccountByUsername(username)
                .compose(account -> {
                    if (account == null) {
                        return Future.failedFuture(invalidCredentials());
                    }
                    return verifyPassword(password, account.passwordHash())
                            .compose(matches -> {
                                if (!matches || !ACTIVE.equals(account.status())) {
                                    return Future.failedFuture(invalidCredentials());
                                }
                                return issueSession(account.id(), account.username(), account.sessionVersion())
                                        .compose(session -> audit(
                                                account.id(), "LOGIN", "SUCCESS", "source=" + safe(source)
                                        ).map(session));
                            });
                })
                .recover(failure -> {
                    if (failure instanceof AuthException) {
                        return audit(null, "LOGIN", "DENIED", "source=" + safe(source))
                                .compose(_ -> Future.failedFuture(failure));
                    }
                    return Future.failedFuture(failure);
                })
                .eventually(() -> {
                    Arrays.fill(password, '\0');
                    return Future.succeededFuture();
                });
    }

    public Future<AdminPrincipal> authenticate(String rawSessionToken) {
        if (rawSessionToken == null || rawSessionToken.isBlank()) {
            return Future.failedFuture(unauthenticated());
        }
        long now = clock.millis();
        return pool.preparedQuery(sql("""
                        SELECT s.id AS session_id,
                               s.admin_account_id,
                               s.csrf_digest,
                               s.idle_expires_at,
                               s.absolute_expires_at,
                               s.last_seen_at,
                               a.username
                        FROM admin_session s
                        JOIN admin_account a ON a.id = s.admin_account_id
                        WHERE s.token_digest = ?
                          AND s.revoked_at IS NULL
                          AND s.idle_expires_at > ?
                          AND s.absolute_expires_at > ?
                          AND s.account_session_version = a.session_version
                          AND a.status = 'ACTIVE'
                        """))
                .execute(Tuple.of(SecurityTokenCodec.digest(rawSessionToken), now, now))
                .compose(rows -> {
                    Row row = first(rows);
                    if (row == null) {
                        return Future.failedFuture(unauthenticated());
                    }
                    long absoluteExpiresAt = number(row, "absolute_expires_at");
                    long currentIdleExpiresAt = number(row, "idle_expires_at");
                    long lastSeenAt = number(row, "last_seen_at");
                    long idleExpiresAt = currentIdleExpiresAt;
                    Future<?> touch = Future.succeededFuture();
                    if (now - lastSeenAt >= Duration.ofMinutes(1).toMillis()) {
                        idleExpiresAt = Math.min(absoluteExpiresAt, now + idleLifetime.toMillis());
                        touch = pool.preparedQuery(sql("""
                                        UPDATE admin_session
                                        SET last_seen_at = ?, idle_expires_at = ?
                                        WHERE id = ? AND revoked_at IS NULL
                                        """))
                                .execute(Tuple.of(now, idleExpiresAt, row.getString("session_id")));
                    }
                    AdminPrincipal principal = new AdminPrincipal(
                            row.getString("session_id"),
                            row.getString("admin_account_id"),
                            row.getString("username"),
                            row.getString("csrf_digest"),
                            idleExpiresAt,
                            absoluteExpiresAt
                    );
                    return touch.map(principal);
                });
    }

    public boolean validateCsrf(AdminPrincipal principal, String rawCsrfToken) {
        return principal != null
               && SecurityTokenCodec.matches(rawCsrfToken, principal.csrfDigest());
    }

    public Future<Boolean> isSessionActive(String sessionId) {
        long now = clock.millis();
        return pool.preparedQuery(sql("""
                        SELECT COUNT(*) AS active_count
                        FROM admin_session s
                        JOIN admin_account a ON a.id = s.admin_account_id
                        WHERE s.id = ?
                          AND s.revoked_at IS NULL
                          AND s.idle_expires_at > ?
                          AND s.absolute_expires_at > ?
                          AND s.account_session_version = a.session_version
                          AND a.status = 'ACTIVE'
                        """))
                .execute(Tuple.of(sessionId, now, now))
                .map(rows -> number(first(rows), "active_count") == 1);
    }

    public Future<Void> logout(AdminPrincipal principal) {
        if (principal == null) {
            return Future.succeededFuture();
        }
        long now = clock.millis();
        return pool.preparedQuery(sql("""
                        UPDATE admin_session
                        SET revoked_at = ?
                        WHERE id = ? AND revoked_at IS NULL
                        """))
                .execute(Tuple.of(now, principal.sessionId()))
                .compose(_ -> audit(
                        principal.accountId(), "LOGOUT", "SUCCESS", "session revoked"
                ));
    }

    public Future<Void> logoutAll(AdminPrincipal principal) {
        if (principal == null) {
            return Future.succeededFuture();
        }
        long now = clock.millis();
        return pool.withTransaction(connection ->
                        connection.preparedQuery(sql("""
                                        UPDATE admin_session
                                        SET revoked_at = ?
                                        WHERE admin_account_id = ? AND revoked_at IS NULL
                                        """))
                                .execute(Tuple.of(now, principal.accountId()))
                                .compose(_ -> connection.preparedQuery(sql("""
                                                UPDATE admin_account
                                                SET session_version = session_version + 1, updated_at = ?
                                                WHERE id = ?
                                                """))
                                        .execute(Tuple.of(now, principal.accountId())))
                )
                .compose(_ -> audit(
                        principal.accountId(), "LOGOUT_ALL", "SUCCESS", "all sessions revoked"
                ));
    }

    public Future<Void> changePassword(
            AdminPrincipal principal,
            char[] requestedCurrentPassword,
            char[] requestedNewPassword
    ) {
        if (principal == null) {
            return Future.failedFuture(unauthenticated());
        }
        char[] currentPassword = copyPassword(requestedCurrentPassword);
        char[] newPassword = copyPassword(requestedNewPassword);
        return findAccountById(principal.accountId())
                .compose(account -> {
                    if (account == null) {
                        return Future.failedFuture(unauthenticated());
                    }
                    return verifyPassword(currentPassword, account.passwordHash())
                            .compose(valid -> {
                                if (!valid) {
                                    return Future.failedFuture(new AuthException(
                                            403, "CURRENT_PASSWORD_INVALID", "Current password is invalid"
                                    ));
                                }
                                return hashPassword(newPassword);
                            });
                })
                .compose(passwordHash -> replacePasswordAndRevoke(
                        principal.accountId(), passwordHash
                ))
                .compose(_ -> audit(
                        principal.accountId(), "PASSWORD_CHANGED", "SUCCESS", "all sessions revoked"
                ))
                .eventually(() -> {
                    Arrays.fill(currentPassword, '\0');
                    Arrays.fill(newPassword, '\0');
                    return Future.succeededFuture();
                });
    }

    public Future<PasswordRecovery> issuePasswordRecovery(String requestedUsername) {
        String username;
        try {
            username = normalizeUsername(requestedUsername);
        } catch (AuthException exception) {
            return Future.failedFuture(exception);
        }
        return findAccountByUsername(username).compose(account -> {
            if (account == null) {
                return Future.failedFuture(new AuthException(
                        404, "ADMIN_NOT_FOUND", "Administrator was not found"
                ));
            }
            String rawToken = SecurityTokenCodec.randomToken(32);
            long now = clock.millis();
            long expiresAt = now + bootstrapLifetime.toMillis();
            return pool.withTransaction(connection ->
                            connection.preparedQuery(sql("""
                                            DELETE FROM admin_recovery_token
                                            WHERE admin_account_id = ?
                                            """))
                                    .execute(Tuple.of(account.id()))
                                    .compose(_ -> connection.preparedQuery(sql("""
                                                    UPDATE admin_session
                                                    SET revoked_at = ?
                                                    WHERE admin_account_id = ? AND revoked_at IS NULL
                                                    """))
                                            .execute(Tuple.of(now, account.id())))
                                    .compose(_ -> connection.preparedQuery(sql("""
                                                    UPDATE admin_account
                                                    SET session_version = session_version + 1,
                                                        updated_at = ?
                                                    WHERE id = ?
                                                    """))
                                            .execute(Tuple.of(now, account.id())))
                                    .compose(_ -> connection.preparedQuery(sql("""
                                                    INSERT INTO admin_recovery_token
                                                        (id, admin_account_id, token_digest,
                                                         expires_at, consumed_at, created_at)
                                                    VALUES (?, ?, ?, ?, NULL, ?)
                                                    """))
                                            .execute(Tuple.of(
                                                    UUID.randomUUID().toString(),
                                                    account.id(),
                                                    SecurityTokenCodec.digest(rawToken),
                                                    expiresAt,
                                                    now
                                            )))
                    )
                    .compose(_ -> audit(
                            account.id(),
                            "PASSWORD_RECOVERY_ISSUED",
                            "SUCCESS",
                            "all sessions revoked"
                    ))
                    .map(new PasswordRecovery(username, rawToken, expiresAt));
        });
    }

    public Future<Void> applyPasswordRecovery(
            String requestedUsername,
            String rawRecoveryToken,
            char[] requestedNewPassword
    ) {
        String username;
        try {
            username = normalizeUsername(requestedUsername);
        } catch (AuthException exception) {
            return Future.failedFuture(exception);
        }
        char[] newPassword = copyPassword(requestedNewPassword);
        return hashPassword(newPassword)
                .compose(passwordHash -> findAccountByUsername(username)
                        .compose(account -> {
                            if (account == null) {
                                return Future.failedFuture(new AuthException(
                                        403,
                                        "PASSWORD_RECOVERY_INVALID",
                                        "Password recovery token is invalid or expired"
                                ));
                            }
                            return consumePasswordRecovery(
                                    account.id(), rawRecoveryToken, passwordHash
                            );
                        }))
                .eventually(() -> {
                    Arrays.fill(newPassword, '\0');
                    return Future.succeededFuture();
                });
    }

    private Future<Void> consumePasswordRecovery(
            String accountId,
            String rawRecoveryToken,
            Argon2idPasswordHasher.PasswordHash passwordHash
    ) {
        long now = clock.millis();
        return pool.withTransaction(connection ->
                        connection.preparedQuery(sql("""
                                        SELECT token_digest, expires_at, consumed_at
                                        FROM admin_recovery_token
                                        WHERE admin_account_id = ?
                                        """))
                                .execute(Tuple.of(accountId))
                                .compose(rows -> {
                                    Row row = first(rows);
                                    if (row == null
                                        || row.getValue("consumed_at") != null
                                        || number(row, "expires_at") <= now
                                        || !SecurityTokenCodec.matches(
                                            rawRecoveryToken,
                                            row.getString("token_digest")
                                    )) {
                                        return Future.failedFuture(new AuthException(
                                                403,
                                                "PASSWORD_RECOVERY_INVALID",
                                                "Password recovery token is invalid or expired"
                                        ));
                                    }
                                    return connection.preparedQuery(sql("""
                                                    UPDATE admin_account
                                                    SET password_hash = ?,
                                                        password_parameters = ?,
                                                        session_version = session_version + 1,
                                                        updated_at = ?
                                                    WHERE id = ?
                                                    """))
                                            .execute(Tuple.of(
                                                    passwordHash.hash(),
                                                    passwordHash.parameters(),
                                                    now,
                                                    accountId
                                            ));
                                })
                                .compose(_ -> connection.preparedQuery(sql("""
                                                UPDATE admin_recovery_token
                                                SET consumed_at = ?
                                                WHERE admin_account_id = ? AND consumed_at IS NULL
                                                """))
                                        .execute(Tuple.of(now, accountId)))
                                .compose(updated -> updated.rowCount() == 1
                                        ? Future.succeededFuture()
                                        : Future.failedFuture(new AuthException(
                                        409,
                                        "PASSWORD_RECOVERY_REPLAYED",
                                        "Password recovery token was already used"
                                )))
                )
                .compose(_ -> audit(
                        accountId,
                        "PASSWORD_RECOVERY_APPLIED",
                        "SUCCESS",
                        "password replaced and all sessions revoked"
                ));
    }

    private Future<Account> createInitialAdmin(
            String rawBootstrapToken,
            String username,
            Argon2idPasswordHasher.PasswordHash passwordHash
    ) {
        long now = clock.millis();
        String accountId = UUID.randomUUID().toString();
        return pool.withTransaction(connection ->
                        connection.preparedQuery(sql("""
                                        SELECT token_digest, expires_at, consumed_at
                                        FROM admin_bootstrap_token
                                        WHERE id = ?
                                        """))
                                .execute(Tuple.of(BOOTSTRAP_ID))
                                .compose(rows -> {
                                    Row row = first(rows);
                                    if (row == null
                                        || row.getValue("consumed_at") != null
                                        || number(row, "expires_at") <= now
                                        || !SecurityTokenCodec.matches(
                                            rawBootstrapToken, row.getString("token_digest")
                                    )) {
                                        return Future.failedFuture(new AuthException(
                                                403, "BOOTSTRAP_TOKEN_INVALID", "Bootstrap token is invalid or expired"
                                        ));
                                    }
                                    return countAdmins(connection);
                                })
                                .compose(count -> {
                                    if (count != 0) {
                                        return Future.failedFuture(new AuthException(
                                                404, "BOOTSTRAP_NOT_AVAILABLE", "Bootstrap is not available"
                                        ));
                                    }
                                    return connection.preparedQuery(sql("""
                                                    INSERT INTO admin_account
                                                        (id, username, password_hash, password_parameters,
                                                         status, session_version, created_at, updated_at)
                                                    VALUES (?, ?, ?, ?, 'ACTIVE', 0, ?, ?)
                                                    """))
                                            .execute(Tuple.of(
                                                    accountId,
                                                    username,
                                                    passwordHash.hash(),
                                                    passwordHash.parameters(),
                                                    now,
                                                    now
                                            ));
                                })
                                .compose(_ -> connection.preparedQuery(sql("""
                                                UPDATE admin_bootstrap_token
                                                SET consumed_at = ?
                                                WHERE id = ? AND consumed_at IS NULL
                                                """))
                                        .execute(Tuple.of(now, BOOTSTRAP_ID)))
                                .compose(updated -> {
                                    if (updated.rowCount() != 1) {
                                        return Future.failedFuture(new AuthException(
                                                409, "BOOTSTRAP_REPLAYED", "Bootstrap token was already used"
                                        ));
                                    }
                                    return Future.succeededFuture();
                                })
                )
                .compose(_ -> audit(
                        accountId, "BOOTSTRAP", "SUCCESS", "initial administrator created"
                ))
                .map(new Account(
                        accountId,
                        username,
                        passwordHash,
                        ACTIVE,
                        0
                ));
    }

    private Future<IssuedSession> issueSession(String accountId, String username, long sessionVersion) {
        long now = clock.millis();
        long absoluteExpiresAt = now + absoluteLifetime.toMillis();
        long idleExpiresAt = Math.min(absoluteExpiresAt, now + idleLifetime.toMillis());
        String sessionId = UUID.randomUUID().toString();
        String rawSessionToken = SecurityTokenCodec.randomToken(32);
        String rawCsrfToken = SecurityTokenCodec.randomToken(24);
        String csrfDigest = SecurityTokenCodec.digest(rawCsrfToken);
        return pool.preparedQuery(sql("""
                        INSERT INTO admin_session
                            (id, admin_account_id, token_digest, csrf_digest,
                             idle_expires_at, absolute_expires_at, last_seen_at,
                             revoked_at, created_at, account_session_version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
                        """))
                .execute(Tuple.of(
                        sessionId,
                        accountId,
                        SecurityTokenCodec.digest(rawSessionToken),
                        csrfDigest,
                        idleExpiresAt,
                        absoluteExpiresAt,
                        now,
                        now,
                        sessionVersion
                ))
                .map(new IssuedSession(
                        new AdminPrincipal(
                                sessionId,
                                accountId,
                                username,
                                csrfDigest,
                                idleExpiresAt,
                                absoluteExpiresAt
                        ),
                        rawSessionToken,
                        rawCsrfToken
                ));
    }

    private Future<Account> findAccountByUsername(String username) {
        return pool.preparedQuery(sql("""
                        SELECT id, username, password_hash, password_parameters, status, session_version
                        FROM admin_account
                        WHERE username = ?
                        """))
                .execute(Tuple.of(username))
                .map(AdminAuthService::mapAccount);
    }

    private Future<Account> findAccountById(String accountId) {
        return pool.preparedQuery(sql("""
                        SELECT id, username, password_hash, password_parameters, status, session_version
                        FROM admin_account
                        WHERE id = ?
                        """))
                .execute(Tuple.of(accountId))
                .map(AdminAuthService::mapAccount);
    }

    private Future<Void> replacePasswordAndRevoke(
            String accountId,
            Argon2idPasswordHasher.PasswordHash passwordHash
    ) {
        long now = clock.millis();
        return pool.withTransaction(connection ->
                        connection.preparedQuery(sql("""
                                        UPDATE admin_account
                                        SET password_hash = ?,
                                            password_parameters = ?,
                                            session_version = session_version + 1,
                                            updated_at = ?
                                        WHERE id = ?
                                        """))
                                .execute(Tuple.of(
                                        passwordHash.hash(),
                                        passwordHash.parameters(),
                                        now,
                                        accountId
                                ))
                                .compose(_ -> connection.preparedQuery(sql("""
                                                UPDATE admin_session
                                                SET revoked_at = ?
                                                WHERE admin_account_id = ? AND revoked_at IS NULL
                                                """))
                                        .execute(Tuple.of(now, accountId)))
                )
                .mapEmpty();
    }

    private Future<Argon2idPasswordHasher.PasswordHash> hashPassword(char[] password) {
        return vertx.executeBlocking(() -> passwordHasher.hash(password));
    }

    private Future<Boolean> verifyPassword(
            char[] password,
            Argon2idPasswordHasher.PasswordHash passwordHash
    ) {
        return vertx.executeBlocking(() -> passwordHasher.verify(password, passwordHash));
    }

    private Future<Long> adminCount() {
        return pool.query("SELECT COUNT(*) AS admin_count FROM admin_account")
                .execute()
                .map(rows -> number(first(rows), "admin_count"));
    }

    private static Future<Long> countAdmins(SqlConnection connection) {
        return connection.query("SELECT COUNT(*) AS admin_count FROM admin_account")
                .execute()
                .map(rows -> number(first(rows), "admin_count"));
    }

    private Future<Void> audit(
            String accountId,
            String eventType,
            String result,
            String summary
    ) {
        return pool.preparedQuery(sql("""
                        INSERT INTO admin_security_event
                            (id, admin_account_id, event_type, result, safe_summary, occurred_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """))
                .execute(Tuple.of(
                        UUID.randomUUID().toString(),
                        accountId,
                        eventType,
                        result,
                        safe(summary),
                        clock.millis()
                ))
                .mapEmpty();
    }

    private static Account mapAccount(RowSet<Row> rows) {
        Row row = first(rows);
        if (row == null) {
            return null;
        }
        return new Account(
                row.getString("id"),
                row.getString("username"),
                new Argon2idPasswordHasher.PasswordHash(
                        row.getString("password_hash"),
                        row.getString("password_parameters")
                ),
                row.getString("status"),
                number(row, "session_version")
        );
    }

    private static Row first(RowSet<Row> rows) {
        return rows == null || !rows.iterator().hasNext() ? null : rows.iterator().next();
    }

    private static long number(Row row, String field) {
        if (row == null || row.getValue(field) == null) {
            return 0;
        }
        return ((Number) row.getValue(field)).longValue();
    }

    private static String normalizeUsername(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(normalized).matches()) {
            throw new AuthException(
                    400,
                    "ADMIN_USERNAME_INVALID",
                    "Username must be 3-64 lowercase letters, digits, dots, underscores or hyphens"
            );
        }
        return normalized;
    }

    private static char[] copyPassword(char[] password) {
        if (password == null) {
            return new char[0];
        }
        return Arrays.copyOf(password, password.length);
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String safe(String value) {
        String redacted = SensitiveDataRedactor.redact(
                value == null ? "unknown" : value.replaceAll("[\\r\\n]", " ")
        );
        return redacted.length() > 512 ? redacted.substring(0, 512) : redacted;
    }

    private static AuthException invalidCredentials() {
        return new AuthException(401, "INVALID_CREDENTIALS", "Invalid username or password");
    }

    private static AuthException unauthenticated() {
        return new AuthException(401, "AUTHENTICATION_REQUIRED", "Authentication is required");
    }

    private record Account(
            String id,
            String username,
            Argon2idPasswordHasher.PasswordHash passwordHash,
            String status,
            long sessionVersion
    ) {
    }
}
