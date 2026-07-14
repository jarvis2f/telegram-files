package telegram.files.security.auth;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.jdbcclient.JDBCConnectOptions;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import telegram.files.repository.AdminAccountRecord;
import telegram.files.repository.AdminBootstrapTokenRecord;
import telegram.files.repository.AdminRecoveryTokenRecord;
import telegram.files.repository.AdminSecurityEventRecord;
import telegram.files.repository.AdminSessionRecord;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class AdminAuthServiceTest {

    @Test
    void bootstrapLoginCsrfAndRevocationLifecycle(
            Vertx vertx,
            VertxTestContext context
    ) {
        Pool pool = JDBCPool.pool(
                vertx,
                new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite::memory:"),
                new PoolOptions().setMaxSize(1)
        );
        AdminAuthService service = new AdminAuthService(vertx, pool);

        createSchema(pool)
                .compose(_ -> service.initialize())
                .compose(bootstrap -> {
                    context.verify(() -> {
                        assertTrue(bootstrap.required());
                        assertNotNull(bootstrap.oneTimeToken());
                        assertFalse(bootstrap.oneTimeToken().isBlank());
                    });
                    return service.bootstrap(
                            bootstrap.oneTimeToken(),
                            "owner",
                            "correct horse battery staple".toCharArray(),
                            true
                    );
                })
                .compose(session -> {
                    context.verify(() -> {
                        assertNotEquals(
                                SecurityTokenCodec.digest(session.sessionToken()),
                                session.sessionToken()
                        );
                        assertTrue(service.validateCsrf(
                                session.principal(),
                                session.csrfToken()
                        ));
                        assertFalse(service.validateCsrf(
                                session.principal(),
                                "wrong-token"
                        ));
                    });
                    return service.authenticate(session.sessionToken())
                            .compose(principal -> service.logout(principal))
                            .compose(_ -> service.authenticate(session.sessionToken())
                                    .map(_ -> false)
                                    .recover(failure -> Future.succeededFuture(true)));
                })
                .compose(revoked -> {
                    context.verify(() -> assertTrue(revoked));
                    return service.login(
                            "owner",
                            "correct horse battery staple".toCharArray(),
                            "127.0.0.1"
                    );
                })
                .compose(loggedIn -> service.changePassword(
                                loggedIn.principal(),
                                "correct horse battery staple".toCharArray(),
                                "new correct horse battery staple".toCharArray()
                        )
                        .compose(_ -> service.authenticate(loggedIn.sessionToken())
                                .map(_ -> false)
                                .recover(failure -> Future.succeededFuture(true))))
                .compose(revokedAfterPasswordChange -> {
                    context.verify(() -> assertTrue(revokedAfterPasswordChange));
                    return service.login(
                            "owner",
                            "new correct horse battery staple".toCharArray(),
                            "127.0.0.1"
                    );
                })
                .compose(newSession -> pool.query("""
                                SELECT token_digest, csrf_digest
                                FROM admin_session
                                WHERE id = '%s'
                                """.formatted(newSession.principal().sessionId()))
                        .execute()
                        .map(rows -> {
                            context.verify(() -> {
                                var row = rows.iterator().next();
                                assertNotEquals(
                                        newSession.sessionToken(),
                                        row.getString("token_digest")
                                );
                                assertNotEquals(
                                        newSession.csrfToken(),
                                        row.getString("csrf_digest")
                                );
                            });
                            return newSession;
                        }))
                .eventually(pool::close)
                .onComplete(context.succeeding(_ -> context.completeNow()));
    }

    @Test
    void localRecoveryTokenRevokesSessionsAndCanOnlyBeUsedOnce(
            Vertx vertx,
            VertxTestContext context
    ) {
        Pool pool = JDBCPool.pool(
                vertx,
                new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite::memory:"),
                new PoolOptions().setMaxSize(1)
        );
        AdminAuthService service = new AdminAuthService(vertx, pool);
        final AdminAuthModels.IssuedSession[] original = new AdminAuthModels.IssuedSession[1];

        createSchema(pool)
                .compose(_ -> service.initialize())
                .compose(bootstrap -> service.bootstrap(
                        bootstrap.oneTimeToken(),
                        "owner",
                        "correct horse battery staple".toCharArray(),
                        true
                ))
                .compose(session -> {
                    original[0] = session;
                    return service.issuePasswordRecovery("owner");
                })
                .compose(recovery -> service.authenticate(original[0].sessionToken())
                        .map(_ -> false)
                        .recover(_ -> Future.succeededFuture(true))
                        .compose(revoked -> {
                            context.verify(() -> assertTrue(revoked));
                            return service.applyPasswordRecovery(
                                    "owner",
                                    recovery.oneTimeToken(),
                                    "new recovery password value".toCharArray()
                            );
                        })
                        .compose(_ -> service.applyPasswordRecovery(
                                        "owner",
                                        recovery.oneTimeToken(),
                                        "another recovery password".toCharArray()
                                )
                                .map(_ -> false)
                                .recover(_ -> Future.succeededFuture(true))))
                .compose(replayRejected -> {
                    context.verify(() -> assertTrue(replayRejected));
                    return service.login(
                            "owner",
                            "new recovery password value".toCharArray(),
                            "127.0.0.1"
                    );
                })
                .eventually(pool::close)
                .onComplete(context.succeeding(_ -> context.completeNow()));
    }

    @Test
    void rejectsRemoteBootstrapAndInvalidCredentials(
            Vertx vertx,
            VertxTestContext context
    ) {
        Pool pool = JDBCPool.pool(
                vertx,
                new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite::memory:"),
                new PoolOptions().setMaxSize(1)
        );
        AdminAuthService service = new AdminAuthService(vertx, pool);

        createSchema(pool)
                .compose(_ -> service.initialize())
                .compose(bootstrap -> service.bootstrap(
                                bootstrap.oneTimeToken(),
                                "owner",
                                "correct horse battery staple".toCharArray(),
                                false
                        )
                        .map(_ -> false)
                        .recover(failure -> Future.succeededFuture(
                                failure instanceof AdminAuthModels.AuthException auth
                                && auth.statusCode() == 403
                        )))
                .compose(remoteRejected -> {
                    context.verify(() -> assertTrue(remoteRejected));
                    return service.login(
                                    "missing",
                                    "correct horse battery staple".toCharArray(),
                                    "127.0.0.1"
                            )
                            .map(_ -> false)
                            .recover(failure -> Future.succeededFuture(
                                    failure instanceof AdminAuthModels.AuthException auth
                                    && auth.statusCode() == 401
                            ));
                })
                .eventually(pool::close)
                .onComplete(context.succeeding(rejected -> context.verify(() -> {
                    assertTrue(rejected);
                    context.completeNow();
                })));
    }

    private static Future<Void> createSchema(Pool pool) {
        List<String> schemes = List.of(
                new AdminAccountRecord.AdminAccountRecordDefinition().getScheme(),
                new AdminSessionRecord.AdminSessionRecordDefinition().getScheme(),
                new AdminBootstrapTokenRecord.AdminBootstrapTokenRecordDefinition().getScheme(),
                new AdminRecoveryTokenRecord.AdminRecoveryTokenRecordDefinition().getScheme(),
                new AdminSecurityEventRecord.AdminSecurityEventRecordDefinition().getScheme()
        );
        Future<Void> result = Future.succeededFuture();
        for (String scheme : schemes) {
            result = result.compose(_ -> pool.query(scheme).execute().mapEmpty());
        }
        return result;
    }
}
