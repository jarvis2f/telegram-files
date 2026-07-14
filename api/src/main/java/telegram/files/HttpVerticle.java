package telegram.files;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.ServerWebSocketHandshake;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.healthchecks.HealthChecks;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.healthchecks.HealthCheckHandler;
import io.vertx.ext.web.sstore.LocalSessionStore;
import io.vertx.ext.web.sstore.SessionStore;
import org.drinkless.tdlib.TdApi;
import org.jooq.lambda.function.Function2;
import telegram.files.repository.SettingAutoRecords;
import telegram.files.repository.SettingKey;
import telegram.files.repository.SettingRecord;
import telegram.files.security.OriginPolicy;
import telegram.files.security.SafePathResolver;
import telegram.files.security.SlidingWindowRateLimiter;
import telegram.files.security.auth.AdminAuthModels.AdminPrincipal;
import telegram.files.security.auth.AdminAuthModels.AuthException;
import telegram.files.security.auth.AdminAuthModels.BootstrapState;
import telegram.files.security.auth.AdminAuthModels.IssuedSession;
import telegram.files.security.auth.AdminAuthService;
import telegram.files.share.UnifiedFileDownloadService;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class HttpVerticle extends AbstractVerticle {

    private static final Log log = LogFactory.get();

    // session id -> ws handler id
    private static final Map<String, String> clients = new ConcurrentHashMap<>();

    // session id -> telegram verticle
    private final Map<String, TelegramVerticle> sessionTelegramVerticles = new ConcurrentHashMap<>();

    private final List<String> unboundClients = new ArrayList<>();

    private final FileRouteHandler fileRouteHandler = new FileRouteHandler();

    private UnifiedFileDownloadService unifiedFileDownloadService;

    void configureUnifiedFileDownloadService(UnifiedFileDownloadService service) {
        this.unifiedFileDownloadService = service;
    }

    private static final String SESSION_COOKIE_NAME = "tf";

    private static final String ADMIN_SESSION_COOKIE_NAME = "tf_admin";

    private static final String CSRF_COOKIE_NAME = "tf_csrf";

    private static final String AUTH_PRINCIPAL_KEY = "adminPrincipal";

    private final OriginPolicy originPolicy = new OriginPolicy(Config.HTTP_ALLOWED_ORIGINS);

    private final SlidingWindowRateLimiter loginRateLimiter = new SlidingWindowRateLimiter(
            Config.AUTH_LOGIN_ATTEMPTS_PER_MINUTE, Duration.ofMinutes(1)
    );

    private final SlidingWindowRateLimiter fileReadRateLimiter = new SlidingWindowRateLimiter(
            Config.FILE_READS_PER_MINUTE, Duration.ofMinutes(1)
    );

    private AdminAuthService adminAuthService;

    private BootstrapState bootstrapState;

    @Override
    public void start(Promise<Void> startPromise) {
        adminAuthService = new AdminAuthService(vertx, DataVerticle.pool);
        adminAuthService.initialize()
                .onSuccess(state -> {
                    bootstrapState = state;
                    if (state.required()) {
                        System.out.println(
                                "Telegram Files one-time bootstrap code (expires in 15 minutes): "
                                + state.oneTimeToken()
                        );
                    }
                })
                .compose(_ -> initHttpServer())
                .compose(_ -> initTelegramVerticles())
                .compose(_ -> AutomationsHolder.INSTANCE.init())
                .compose(_ -> initAutoDownloadVerticle())
                .compose(_ -> initTransferVerticle())
                .compose(_ -> initPreloadMessageVerticle())
                .compose(_ -> initEventConsumer())
                .onSuccess(startPromise::complete)
                .onFailure(startPromise::fail);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        AutomationsHolder.INSTANCE.saveAutoRecords()
                .onComplete(ignore -> {
                    System.out.println("Http verticle stopped!");
                    stopPromise.complete();
                });
    }

    public Future<Void> initHttpServer() {
        int port = config().getInteger("http.port", 8080);
        String host = Config.HTTP_HOST;
        HttpServerOptions options = new HttpServerOptions()
                .setHost(host)
                .setLogActivity(true)
                .setRegisterWebSocketWriteHandlers(true)
                .setMaxWebSocketMessageSize(1024 * 1024)
                .setIdleTimeout(60)
                .setIdleTimeoutUnit(TimeUnit.SECONDS)
                .setPort(port);

        return vertx.createHttpServer(options)
                .webSocketHandshakeHandler(this::handleWebSocketHandshake)
                .requestHandler(initRouter())
                .listen()
                .onSuccess(_ -> log.info("API server started on {}:{}", host, port))
                .onFailure(err -> log.error("Failed to start API server: %s".formatted(err.getMessage())))
                .mapEmpty();
    }

    public Router initRouter() {
        Router router = Router.router(vertx);

        SessionStore sessionStore = LocalSessionStore.create(vertx, SESSION_COOKIE_NAME);
        SessionHandler sessionHandler = SessionHandler.create(sessionStore)
                .setSessionCookieName(SESSION_COOKIE_NAME)
                .setCookieSameSite(CookieSameSite.STRICT)
                .setCookieSecureFlag(Config.HTTP_SECURE_COOKIES);
        router.route()
                .handler(sessionHandler)
                .handler(BodyHandler.create().setBodyLimit(Config.HTTP_BODY_LIMIT_BYTES));

        if (!originPolicy.allowedOrigins().isEmpty()) {
            CorsHandler corsHandler = CorsHandler.create()
                    .allowedMethod(HttpMethod.GET)
                    .allowedMethod(HttpMethod.POST)
                    .allowedMethod(HttpMethod.PUT)
                    .allowedMethod(HttpMethod.PATCH)
                    .allowedMethod(HttpMethod.DELETE)
                    .allowedMethod(HttpMethod.OPTIONS)
                    .allowCredentials(true)
                    .allowedHeader("Content-Type")
                    .allowedHeader("X-CSRF-Token");
            originPolicy.allowedOrigins().forEach(corsHandler::addOrigin);
            router.route().handler(corsHandler);
        }
        router.route().handler(this::handleOrigin);

        HealthChecks hc = HealthChecks.create(vertx);
        hc.register("http-server", Promise::complete);

        router.get("/health").handler(HealthCheckHandler.createWithHealthChecks(hc));
        router.get("/version").handler(ctx -> ctx.json(new JsonObject().put("version", Start.VERSION)));
        router.get("/auth/bootstrap/status").handler(this::handleBootstrapStatus);
        router.post("/auth/bootstrap").handler(this::handleBootstrap);
        router.post("/auth/login").handler(this::handleLogin);
        router.options().handler(ctx -> ctx.response().setStatusCode(204).end());

        router.route()
                .handler(this::handleAuthentication)
                .handler(this::handleCsrf);

        router.get("/auth/session").handler(this::handleSession);
        router.post("/auth/logout").handler(this::handleLogout);
        router.post("/auth/logout-all").handler(this::handleLogoutAll);
        router.post("/auth/password").handler(this::handlePasswordChange);

        router.post("/share/device/authorize").handler(this::handleShareDeviceAuthorize);
        router.get("/share/device/status").handler(ctx -> requestShareCommand(
                ctx, EventEnum.SHARE_DEVICE_STATUS, new JsonObject()
        ));
        router.post("/share/device/cancel").handler(ctx -> requestShareCommand(
                ctx, EventEnum.SHARE_DEVICE_CANCEL, new JsonObject()
        ));
        router.delete("/share/node").handler(ctx -> requestShareCommand(
                ctx, EventEnum.SHARE_NODE_UNBIND, new JsonObject()
        ));
        router.put("/share/node/name").handler(this::handleShareNodeRename);
        router.get("/share/resources").handler(this::handleShareResourceList);
        router.get("/share/publication-policy").handler(ctx -> requestShareCommand(
                ctx, EventEnum.SHARE_PUBLICATION_POLICY, new JsonObject()
        ));
        router.post("/share/resources").handler(this::handleShareResourcePublish);
        router.put("/share/resources/:sourceId").handler(this::handleShareResourceUpdate);
        router.delete("/share/resources/:sourceId").handler(this::handleShareResourceRevoke);

        router.get("/").handler(ctx -> ctx.response().end("Hello World!"));
        router.get("/settings").handler(this::handleSettings);
        router.post("/settings/create").handler(this::handleSettingsCreate);
        router.get("/automations/chats").handler(this::handleAutomationChats);

        router.post("/telegram/create").handler(this::handleTelegramCreate);
        router.post("/telegram/:telegramId/delete").handler(this::handleTelegramDelete);
        router.get("/telegram/api/methods").handler(this::handleTelegramApiMethods);
        router.get("/telegram/api/:method/parameters").handler(this::handleTelegramApiMethodParameters);
        router.post("/telegram/api/:method").handler(this::handleTelegramApi);
        router.get("/telegrams").handler(this::handleTelegrams);
        router.get("/telegram/:telegramId/chats").handler(this::handleTelegramChats);
        router.get("/telegram/:telegramId/chat/:chatId/files").handler(this::handleTelegramFiles);
        router.get("/telegram/:telegramId/chat/:chatId/files/count").handler(this::handleTelegramFilesCount);
        router.get("/telegram/:telegramId/download-statistics").handler(this::handleTelegramDownloadStatistics);
        router.post("/telegrams/change").handler(this::handleTelegramChange);
        router.post("/telegram/:telegramId/toggle-proxy").handler(this::handleTelegramToggleProxy);
        router.get("/telegram/:telegramId/ping").handler(this::handleTelegramPing);
        router.get("/telegram/:telegramId/test-network").handler(this::handleTelegramTestNetwork);

        router.get("/:telegramId/file/:uniqueId").handler(this::handleFilePreview);
        router.post("/:telegramId/file/start-download").handler(this::handleFileStartDownload);
        router.post("/:telegramId/file/cancel-download").handler(this::handleFileCancelDownload);
        router.post("/:telegramId/file/toggle-pause-download").handler(this::handleFileTogglePauseDownload);
        router.post("/:telegramId/file/remove").handler(this::handleFileRemove);
        router.post("/:telegramId/file/update-auto-settings").handler(this::handleAutoSettingsUpdate);

        router.get("/files/count").handler(this::handleFilesCount);
        router.get("/files").handler(this::handleFiles);
        router.post("/files/start-download-multiple").handler(this::handleFileStartDownloadMultiple);
        router.post("/files/cancel-download-multiple").handler(this::handleFileCancelDownloadMultiple);
        router.post("/files/toggle-pause-download-multiple").handler(this::handleFileTogglePauseDownloadMultiple);
        router.post("/files/set-upload-limit-multiple").handler(this::handleFileSetUploadLimitMultiple);
        router.post("/files/remove-multiple").handler(this::handleFileRemoveMultiple);
        router.post("/files/update-tags").handler(this::handleFileTagsUpdateMultiple);
        router.post("/file/:uniqueId/update-tags").handler(this::handleFileTagsUpdate);

        router.route()
                .failureHandler(ctx -> {
                    int statusCode = ctx.statusCode();
                    if (statusCode < 500) {
                        if (ctx.response().ended()) {
                            return;
                        }
                        ctx.response().setStatusCode(statusCode).end();
                        return;
                    }
                    Throwable throwable = ctx.failure();
                    log.trace("route: %s, statusCode: %d".formatted(
                            ctx.request().path(),
                            statusCode), throwable);
                    HttpServerResponse response = ctx.response();
                    response.setStatusCode(statusCode)
                            .putHeader("Content-Type", "application/json")
                            .end(JsonObject.of("error", throwable == null ? "☹️Sorry! Not today." : throwable.getMessage()).encode());
                });
        return router;
    }

    public Future<Void> initTelegramVerticles() {
        return TelegramVerticles.initTelegramVerticles(vertx);
    }

    public Future<Void> initAutoDownloadVerticle() {
        return vertx.deployVerticle(new AutoDownloadVerticle(), Config.VIRTUAL_THREAD_DEPLOYMENT_OPTIONS)
                .mapEmpty();
    }

    public Future<Void> initTransferVerticle() {
        return vertx.deployVerticle(new TransferVerticle(), Config.VIRTUAL_THREAD_DEPLOYMENT_OPTIONS)
                .mapEmpty();
    }

    public Future<Void> initPreloadMessageVerticle() {
        return vertx.deployVerticle(new PreloadMessageVerticle(), Config.VIRTUAL_THREAD_DEPLOYMENT_OPTIONS)
                .mapEmpty();
    }

    private Future<Void> initEventConsumer() {
        vertx.eventBus().consumer(EventEnum.TELEGRAM_EVENT.address(), message -> {
            log.debug("Received telegram event: %s".formatted(message.body()));
            JsonObject jsonObject = (JsonObject) message.body();
            String telegramId = jsonObject.getString("telegramId");
            EventPayload payload = jsonObject.getJsonObject("payload").mapTo(EventPayload.class);

            Set<String> sentSessionIds = new HashSet<>();
            sessionTelegramVerticles.entrySet().stream()
                    .filter(e -> Objects.equals(Convert.toStr(e.getValue().getId()), telegramId))
                    .map(Map.Entry::getKey)
                    .forEach(sessionId -> {
                        String wsHandlerId = clients.get(sessionId);
                        if (StrUtil.isNotBlank(wsHandlerId)) {
                            vertx.eventBus().send(wsHandlerId, Json.encode(payload));
                        }
                        sentSessionIds.add(sessionId);
                    });

            unboundClients.forEach(sessionId -> {
                if (sentSessionIds.contains(sessionId)) {
                    return;
                }
                String wsHandlerId = clients.get(sessionId);
                if (StrUtil.isNotBlank(wsHandlerId)) {
                    vertx.eventBus().send(wsHandlerId, Json.encode(payload));
                }
            });
        });

        vertx.eventBus().consumer(EventEnum.AUTO_DOWNLOAD_UPDATE.address(), message -> {
            log.debug("Auto settings update: %s".formatted(message.body()));
            AutomationsHolder.INSTANCE.onAutoRecordsUpdate(Json.decodeValue(message.body().toString(), SettingAutoRecords.class));
        });
        return Future.succeededFuture();
    }

    private void handleOrigin(RoutingContext ctx) {
        String origin = ctx.request().getHeader("Origin");
        boolean configuredOrigin = originPolicy.isAllowed(origin);
        boolean implicitSameOrigin = originPolicy.allowedOrigins().isEmpty()
                                     && isSameOriginRequest(ctx, origin);
        if (!configuredOrigin && !implicitSameOrigin) {
            respondJson(ctx, 403, "ORIGIN_NOT_ALLOWED", "Request origin is not allowed");
            return;
        }
        ctx.next();
    }

    private static boolean isSameOriginRequest(RoutingContext ctx, String rawOrigin) {
        if (StrUtil.isBlank(rawOrigin)) {
            return true;
        }
        try {
            URI origin = URI.create(rawOrigin);
            String directHost = ctx.request().remoteAddress() == null
                    ? "unknown"
                    : ctx.request().remoteAddress().host();
            boolean trustedProxy = OriginPolicy.isLoopback(directHost);
            String expectedScheme = trustedProxy
                    ? ctx.request().getHeader("X-Forwarded-Proto")
                    : null;
            expectedScheme = StrUtil.blankToDefault(expectedScheme, "http");
            String expectedAuthority = trustedProxy
                    ? ctx.request().getHeader("X-Forwarded-Host")
                    : null;
            expectedAuthority = StrUtil.blankToDefault(
                    expectedAuthority,
                    ctx.request().getHeader("Host")
            );
            return expectedAuthority != null
                   && expectedScheme.equalsIgnoreCase(origin.getScheme())
                   && expectedAuthority.equalsIgnoreCase(origin.getRawAuthority());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void handleBootstrapStatus(RoutingContext ctx) {
        adminAuthService.bootstrapRequired()
                .onSuccess(required -> ctx.json(JsonObject.of("required", required)))
                .onFailure(failure -> respondFailure(ctx, failure));
    }

    private void handleBootstrap(RoutingContext ctx) {
        String source = remoteHost(ctx);
        if (!acquire(loginRateLimiter, "bootstrap:" + source, ctx)) {
            return;
        }
        JsonObject body = requestBody(ctx);
        if (body == null) {
            return;
        }
        String bootstrapToken = body.getString("bootstrapToken");
        String username = body.getString("username");
        String password = body.getString("password");
        adminAuthService.bootstrap(
                        bootstrapToken,
                        username,
                        password == null ? null : password.toCharArray(),
                        OriginPolicy.isLocalNetwork(source)
                )
                .onSuccess(session -> {
                    addSessionCookies(ctx, session);
                    ctx.response().setStatusCode(201).end(sessionBody(session).encode());
                })
                .onFailure(failure -> respondFailure(ctx, failure));
    }

    private void handleLogin(RoutingContext ctx) {
        JsonObject body = requestBody(ctx);
        if (body == null) {
            return;
        }
        String username = body.getString("username");
        String source = remoteHost(ctx);
        if (!acquire(
                loginRateLimiter,
                "login:" + source + ":" + String.valueOf(username).toLowerCase(Locale.ROOT),
                ctx
        )) {
            return;
        }
        String password = body.getString("password");
        adminAuthService.login(
                        username,
                        password == null ? null : password.toCharArray(),
                        source
                )
                .onSuccess(session -> {
                    addSessionCookies(ctx, session);
                    ctx.response().end(sessionBody(session).encode());
                })
                .onFailure(failure -> respondFailure(ctx, failure));
    }

    private void handleAuthentication(RoutingContext ctx) {
        Cookie cookie = ctx.request().getCookie(ADMIN_SESSION_COOKIE_NAME);
        if (cookie == null) {
            respondJson(ctx, 401, "AUTHENTICATION_REQUIRED", "Authentication is required");
            return;
        }
        adminAuthService.authenticate(cookie.getValue())
                .onSuccess(principal -> {
                    ctx.put(AUTH_PRINCIPAL_KEY, principal);
                    ctx.next();
                })
                .onFailure(failure -> {
                    clearSessionCookies(ctx);
                    respondFailure(ctx, failure);
                });
    }

    private void handleCsrf(RoutingContext ctx) {
        if (Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS)
                .contains(ctx.request().method())) {
            ctx.next();
            return;
        }
        AdminPrincipal principal = principal(ctx);
        if (!adminAuthService.validateCsrf(
                principal,
                ctx.request().getHeader("X-CSRF-Token")
        )) {
            log.debug("Rejected request: CSRF token is invalid for administrator %s".formatted(principal.username()));
            respondJson(ctx, 403, "CSRF_TOKEN_INVALID", "CSRF token is invalid");
            return;
        }
        ctx.next();
    }

    private void handleSession(RoutingContext ctx) {
        AdminPrincipal principal = principal(ctx);
        ctx.json(JsonObject.of(
                "authenticated", true,
                "username", principal.username(),
                "idleExpiresAt", principal.idleExpiresAt(),
                "absoluteExpiresAt", principal.absoluteExpiresAt()
        ));
    }

    private void handleLogout(RoutingContext ctx) {
        AdminPrincipal principal = principal(ctx);
        adminAuthService.logout(principal)
                .onSuccess(_ -> {
                    clearSessionCookies(ctx);
                    ctx.response().setStatusCode(204).end();
                })
                .onFailure(failure -> respondFailure(ctx, failure));
    }

    private void handleLogoutAll(RoutingContext ctx) {
        AdminPrincipal principal = principal(ctx);
        adminAuthService.logoutAll(principal)
                .onSuccess(_ -> {
                    clearSessionCookies(ctx);
                    ctx.response().setStatusCode(204).end();
                })
                .onFailure(failure -> respondFailure(ctx, failure));
    }

    private void handlePasswordChange(RoutingContext ctx) {
        JsonObject body = requestBody(ctx);
        if (body == null) {
            return;
        }
        String currentPassword = body.getString("currentPassword");
        String newPassword = body.getString("newPassword");
        adminAuthService.changePassword(
                        principal(ctx),
                        currentPassword == null ? null : currentPassword.toCharArray(),
                        newPassword == null ? null : newPassword.toCharArray()
                )
                .onSuccess(_ -> {
                    clearSessionCookies(ctx);
                    ctx.response().setStatusCode(204).end();
                })
                .onFailure(failure -> respondFailure(ctx, failure));
    }

    private void handleShareDeviceAuthorize(RoutingContext ctx) {
        JsonObject body = requestBody(ctx);
        if (body != null) {
            requestShareCommand(ctx, EventEnum.SHARE_DEVICE_AUTHORIZE, body);
        }
    }

    private void handleShareNodeRename(RoutingContext ctx) {
        JsonObject body = requestBody(ctx);
        if (body != null) {
            requestShareCommand(ctx, EventEnum.SHARE_NODE_RENAME, body);
        }
    }

    private void handleShareResourcePublish(RoutingContext ctx) {
        JsonObject body = requestBody(ctx);
        if (body != null) {
            requestShareCommand(ctx, EventEnum.SHARE_RESOURCE_PUBLISH, body);
        }
    }

    private void handleShareResourceList(RoutingContext ctx) {
        requestShareCommand(
                ctx,
                EventEnum.SHARE_RESOURCE_LIST,
                new JsonObject()
                        .put("page", Convert.toInt(ctx.request().getParam("page"), 1))
                        .put("pageSize", Convert.toInt(ctx.request().getParam("pageSize"), 10))
        );
    }

    private void handleShareResourceUpdate(RoutingContext ctx) {
        JsonObject body = requestBody(ctx);
        if (body != null) {
            body.put("sourceId", ctx.pathParam("sourceId"));
            requestShareCommand(ctx, EventEnum.SHARE_RESOURCE_UPDATE, body);
        }
    }

    private void handleShareResourceRevoke(RoutingContext ctx) {
        requestShareCommand(
                ctx,
                EventEnum.SHARE_RESOURCE_REVOKE,
                new JsonObject().put("sourceId", ctx.pathParam("sourceId"))
        );
    }

    private void requestShareCommand(RoutingContext ctx, EventEnum event, JsonObject body) {
        if (!Config.shareConfiguration().enabled()) {
            respondJson(ctx, 404, "SHARE_DISABLED", "Share module is disabled");
            return;
        }
        vertx.eventBus().<JsonObject>request(event.address(), body)
                .onSuccess(message -> ctx.json(message.body()))
                .onFailure(failure -> respondJson(
                        ctx,
                        400,
                        "SHARE_COMMAND_FAILED",
                        failure.getMessage() == null ? "Share command failed" : failure.getMessage()
                ));
    }

    private boolean acquire(
            SlidingWindowRateLimiter limiter,
            String key,
            RoutingContext ctx
    ) {
        if (limiter.tryAcquire(key)) {
            return true;
        }
        ctx.response().putHeader(
                "Retry-After",
                String.valueOf(limiter.retryAfterSeconds(key))
        );
        respondJson(ctx, 429, "RATE_LIMITED", "Too many requests");
        return false;
    }

    private JsonObject requestBody(RoutingContext ctx) {
        try {
            JsonObject body = ctx.body().asJsonObject();
            if (body == null) {
                respondJson(ctx, 400, "REQUEST_BODY_REQUIRED", "JSON request body is required");
            }
            return body;
        } catch (RuntimeException exception) {
            respondJson(ctx, 400, "REQUEST_BODY_INVALID", "JSON request body is invalid");
            return null;
        }
    }

    private void addSessionCookies(RoutingContext ctx, IssuedSession session) {
        long maxAgeSeconds = Math.max(
                1,
                (session.principal().absoluteExpiresAt() - System.currentTimeMillis()) / 1_000
        );
        ctx.response().addCookie(
                Cookie.cookie(ADMIN_SESSION_COOKIE_NAME, session.sessionToken())
                        .setHttpOnly(true)
                        .setSecure(Config.HTTP_SECURE_COOKIES)
                        .setSameSite(CookieSameSite.STRICT)
                        .setPath("/")
                        .setMaxAge(maxAgeSeconds)
        );
        ctx.response().addCookie(
                Cookie.cookie(CSRF_COOKIE_NAME, session.csrfToken())
                        .setHttpOnly(false)
                        .setSecure(Config.HTTP_SECURE_COOKIES)
                        .setSameSite(CookieSameSite.STRICT)
                        .setPath("/")
                        .setMaxAge(maxAgeSeconds)
        );
    }

    private static void clearSessionCookies(RoutingContext ctx) {
        ctx.response().addCookie(
                Cookie.cookie(ADMIN_SESSION_COOKIE_NAME, "")
                        .setHttpOnly(true)
                        .setSecure(Config.HTTP_SECURE_COOKIES)
                        .setSameSite(CookieSameSite.STRICT)
                        .setPath("/")
                        .setMaxAge(0)
        );
        ctx.response().addCookie(
                Cookie.cookie(CSRF_COOKIE_NAME, "")
                        .setHttpOnly(false)
                        .setSecure(Config.HTTP_SECURE_COOKIES)
                        .setSameSite(CookieSameSite.STRICT)
                        .setPath("/")
                        .setMaxAge(0)
        );
    }

    private static JsonObject sessionBody(IssuedSession session) {
        return JsonObject.of(
                "authenticated", true,
                "username", session.principal().username(),
                "idleExpiresAt", session.principal().idleExpiresAt(),
                "absoluteExpiresAt", session.principal().absoluteExpiresAt()
        );
    }

    private static AdminPrincipal principal(RoutingContext ctx) {
        return ctx.get(AUTH_PRINCIPAL_KEY);
    }

    private static String remoteHost(RoutingContext ctx) {
        String directHost = ctx.request().remoteAddress() == null
                ? "unknown"
                : ctx.request().remoteAddress().host();
        if (OriginPolicy.isLoopback(directHost)) {
            String proxiedHost = ctx.request().getHeader("X-Real-IP");
            if (StrUtil.isNotBlank(proxiedHost)) {
                return proxiedHost.trim();
            }
        }
        return directHost;
    }

    private static void respondJson(
            RoutingContext ctx,
            int statusCode,
            String errorCode,
            String message
    ) {
        if (ctx.response().ended()) {
            return;
        }
        ctx.response()
                .setStatusCode(statusCode)
                .putHeader("Content-Type", "application/json")
                .end(JsonObject.of(
                        "error", JsonObject.of(
                                "code", errorCode,
                                "message", message
                        )
                ).encode());
    }

    private static void respondFailure(RoutingContext ctx, Throwable failure) {
        if (failure instanceof AuthException authException) {
            respondJson(
                    ctx,
                    authException.statusCode(),
                    authException.errorCode(),
                    authException.getMessage()
            );
            return;
        }
        log.error("Security request failed", failure);
        respondJson(ctx, 500, "INTERNAL_ERROR", "The request could not be completed");
    }

    private void handleWebSocketHandshake(ServerWebSocketHandshake handshake) {
        if (!"/ws".equals(handshake.path())) {
            rejectWebSocket(handshake, 404, "WebSocket path is not supported");
            return;
        }
        if (!isWebSocketOriginAllowed(handshake)) {
            rejectWebSocket(handshake, 403, "WebSocket origin is not allowed");
            return;
        }

        String adminSessionToken = cookieValue(handshake.headers(), ADMIN_SESSION_COOKIE_NAME);
        if (StrUtil.isBlank(adminSessionToken)) {
            rejectWebSocket(handshake, 401, "administrator session cookie is missing");
            return;
        }

        String webSessionId = cookieValue(handshake.headers(), SESSION_COOKIE_NAME);
        String telegramId = queryParameter(handshake.query(), "telegramId");
        adminAuthService.authenticate(adminSessionToken)
                .onSuccess(adminPrincipal -> {
                    String sessionId = StrUtil.blankToDefault(webSessionId, adminPrincipal.sessionId());
                    handshake.accept()
                            .onSuccess(ws -> initializeWebSocket(ws, adminPrincipal, sessionId, telegramId))
                            .onFailure(failure -> log.error("Failed to accept authenticated WebSocket", failure));
                })
                .onFailure(failure -> {
                    int statusCode = failure instanceof AuthException authException
                            ? authException.statusCode()
                            : 500;
                    rejectWebSocket(handshake, statusCode, "administrator session cookie is invalid or expired");
                });
    }

    private boolean isWebSocketOriginAllowed(ServerWebSocketHandshake handshake) {
        String origin = handshake.headers().get("Origin");
        if (originPolicy.isAllowed(origin)) {
            return true;
        }
        return originPolicy.allowedOrigins().isEmpty()
               && isSameOriginHandshake(handshake, origin);
    }

    private static boolean isSameOriginHandshake(ServerWebSocketHandshake handshake, String rawOrigin) {
        if (StrUtil.isBlank(rawOrigin)) {
            return true;
        }
        try {
            URI origin = URI.create(rawOrigin);
            String directHost = handshake.remoteAddress() == null
                    ? "unknown"
                    : handshake.remoteAddress().host();
            boolean trustedProxy = OriginPolicy.isLoopback(directHost);
            String expectedScheme = trustedProxy
                    ? handshake.headers().get("X-Forwarded-Proto")
                    : null;
            expectedScheme = StrUtil.blankToDefault(expectedScheme, handshake.scheme());
            expectedScheme = StrUtil.blankToDefault(
                    expectedScheme,
                    handshake.isSsl() ? "https" : "http"
            );
            String expectedAuthority = trustedProxy
                    ? handshake.headers().get("X-Forwarded-Host")
                    : null;
            expectedAuthority = StrUtil.blankToDefault(
                    expectedAuthority,
                    handshake.headers().get("Host")
            );
            return expectedAuthority != null
                   && expectedScheme.equalsIgnoreCase(origin.getScheme())
                   && expectedAuthority.equalsIgnoreCase(origin.getRawAuthority());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void rejectWebSocket(
            ServerWebSocketHandshake handshake,
            int statusCode,
            String reason
    ) {
        handshake.reject(statusCode)
                .onFailure(failure -> log.error("Failed to reject WebSocket handshake", failure));
    }

    static String cookieValue(MultiMap headers, String name) {
        for (String header : headers.getAll("Cookie")) {
            for (String cookie : header.split(";")) {
                int separator = cookie.indexOf('=');
                if (separator <= 0 || !name.equals(cookie.substring(0, separator).trim())) {
                    continue;
                }
                String value = cookie.substring(separator + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    return value.substring(1, value.length() - 1);
                }
                return value;
            }
        }
        return null;
    }

    static String queryParameter(String query, String name) {
        if (StrUtil.isBlank(query)) {
            return null;
        }
        for (String parameter : query.split("&")) {
            int separator = parameter.indexOf('=');
            String key = separator < 0 ? parameter : parameter.substring(0, separator);
            if (!name.equals(URLUtil.decode(key))) {
                continue;
            }
            return separator < 0 ? "" : URLUtil.decode(parameter.substring(separator + 1));
        }
        return null;
    }

    private void initializeWebSocket(
            ServerWebSocket ws,
            AdminPrincipal adminPrincipal,
            String sessionId,
            String telegramId
    ) {
        try {
            String textHandlerId = ws.textHandlerID();
            if (textHandlerId == null) {
                log.error("Failed to initialize authenticated WebSocket: text handler is unavailable");
                ws.close();
                return;
            }
            clients.put(sessionId, textHandlerId);
            if (!handleTelegramChange(sessionId, telegramId)) {
                log.debug("Failed to change Telegram account for WebSocket connection");
            }
            if (StrUtil.isBlank(telegramId)) {
                if (!unboundClients.contains(sessionId)) {
                    unboundClients.add(sessionId);
                }
            } else {
                unboundClients.remove(sessionId);
            }

            long timerId = vertx.setPeriodic(30000, _ -> {
                if (ws.isClosed()) {
                    return;
                }
                adminAuthService.isSessionActive(adminPrincipal.sessionId())
                        .onSuccess(active -> {
                            if (!active) {
                                ws.close();
                                return;
                            }
                            ws.writePing(Buffer.buffer("👀"));
                        })
                        .onFailure(_ -> ws.close());
            });

            ws.exceptionHandler(throwable -> log.error("WebSocket error: %s".formatted(throwable.getMessage())));
            ws.closeHandler(_ -> {
                clients.remove(sessionId, textHandlerId);
                sessionTelegramVerticles.remove(sessionId);
                unboundClients.remove(sessionId);
                vertx.cancelTimer(timerId);
            });

            ws.textMessageHandler(text -> log.debug("Received WebSocket message: " + text));
        } catch (RuntimeException exception) {
            log.error("Failed to initialize authenticated WebSocket", exception);
            ws.close();
        }
    }

    private void handleSettingsCreate(RoutingContext ctx) {
        JsonObject object = ctx.body().asJsonObject();
        if (CollUtil.isEmpty(object)) {
            ctx.fail(400);
            return;
        }

        Future.all(object.stream()
                        .map(setting -> DataVerticle.settingRepository.createOrUpdate(setting.getKey(),
                                Convert.toStr(setting.getValue(), "")))
                        .toList())
                .map(CompositeFuture::<SettingRecord>list)
                .onSuccess(records -> {
                    records.forEach(record ->
                            vertx.eventBus().publish(EventEnum.SETTING_UPDATE.address(record.key()), record.value()));
                    ctx.end();
                })
                .onFailure(ctx::fail);
    }

    private void handleSettings(RoutingContext ctx) {
        String keysStr = ctx.request().getParam("keys");
        if (StrUtil.isBlank(keysStr)) {
            ctx.fail(400);
            return;
        }
        List<String> keys = Arrays.asList(keysStr.split(","));
        DataVerticle.settingRepository
                .getByKeys(keys)
                .onSuccess(settings -> {
                    JsonObject object = new JsonObject();
                    for (SettingRecord record : settings) {
                        object.put(record.key(), record.value());
                    }
                    for (String key : keys) {
                        if (object.containsKey(key)) {
                            continue;
                        }
                        if ("shareEnabled".equals(key)) {
                            object.put("shareEnabled", Config.shareConfiguration().enabled());
                            continue;
                        }
                        object.put(key, SettingKey.valueOf(key).defaultValue);
                    }
                    if (keys.contains("shareEnabled")) {
                        object.put("shareEnabled", Config.shareConfiguration().enabled());
                    }
                    ctx.json(object);
                })
                .onFailure(ctx::fail);
    }

    private void handleTelegramCreate(RoutingContext ctx) {
        String sessionId = ctx.session().id();
        TelegramVerticle telegramVerticle = sessionTelegramVerticles.get(sessionId);
        boolean isClosedOrClosing = telegramVerticle != null &&
                                    telegramVerticle.lastAuthorizationState != null &&
                                    (telegramVerticle.lastAuthorizationState.getConstructor() == TdApi.AuthorizationStateClosed.CONSTRUCTOR ||
                                     telegramVerticle.lastAuthorizationState.getConstructor() == TdApi.AuthorizationStateClosing.CONSTRUCTOR);
        if (telegramVerticle != null && !telegramVerticle.authorized && !isClosedOrClosing) {
            ctx.json(new JsonObject()
                    .put("id", telegramVerticle.getId())
                    .put("lastState", telegramVerticle.lastAuthorizationState)
            );
            return;
        }
        JsonObject jsonObject = ctx.body().asJsonObject();
        String proxyName = jsonObject.getString("proxyName");

        TelegramVerticle newTelegramVerticle = TelegramVerticles.create(DataVerticle.telegramRepository.getRootPath());
        newTelegramVerticle.setProxy(proxyName);
        sessionTelegramVerticles.put(sessionId, newTelegramVerticle);
        TelegramVerticles.add(newTelegramVerticle);
        vertx.deployVerticle(newTelegramVerticle)
                .onSuccess(_ -> ctx.json(new JsonObject()
                        .put("id", newTelegramVerticle.getId())
                        .put("lastState", newTelegramVerticle.lastAuthorizationState)
                ))
                .onFailure(ctx::fail);
    }

    private void handleTelegramDelete(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticleByPath(ctx);
        if (telegramVerticle == null) {
            return;
        }
        telegramVerticle.close(true)
                .onSuccess(_ -> {
                    TelegramVerticles.remove(telegramVerticle);
                    sessionTelegramVerticles.entrySet().removeIf(e -> e.getValue().equals(telegramVerticle));
                    ctx.end();
                });
    }

    private void handleTelegrams(RoutingContext ctx) {
        Boolean authorized = Convert.toBool(ctx.request().getParam("authorized"));
        Future.all(TelegramVerticles.getAll().stream()
                        .filter(c -> authorized == null || c.authorized == authorized)
                        .map(TelegramVerticle::getTelegramAccount)
                        .toList()
                )
                .map(CompositeFuture::list)
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleTelegramChats(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticleByPath(ctx);
        if (telegramVerticle == null) {
            return;
        }
        String query = ctx.request().getParam("query");
        String chatId = ctx.request().getParam("chatId");
        String archived = ctx.request().getParam("archived");
        telegramVerticle.getChats(Convert.toLong(chatId), query, Convert.toBool(archived, false))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleAutomationChats(RoutingContext ctx) {
        List<SettingAutoRecords.Automation> enabledAutomations = AutomationsHolder.INSTANCE.autoRecords().automations.stream()
                .filter(automation -> (automation.preload != null && automation.preload.enabled)
                                      || (automation.download != null && automation.download.enabled)
                                      || (automation.transfer != null && automation.transfer.enabled))
                .toList();

        List<JsonObject> overviewItems = enabledAutomations.stream()
                .map(automation -> {
                    Optional<TelegramVerticle> telegramVerticleOptional = TelegramVerticles.get(automation.telegramId);
                    JsonObject item = new JsonObject()
                            .put("telegramId", Convert.toStr(automation.telegramId))
                            .put("chatId", Convert.toStr(automation.chatId))
                            .put("auto", automation);

                    telegramVerticleOptional.ifPresent(telegramVerticle -> {
                        item.put("accountName", accountDisplayName(telegramVerticle));

                        TdApi.Chat chat = telegramVerticle.getChat(automation.chatId);
                        if (chat != null) {
                            item.put("chatName", chat.id == automation.telegramId ? "Saved Messages" : chat.title)
                                    .put("chatType", TdApiHelp.getChatType(chat.type))
                                    .put("chatAvatar", minithumbnail(chat))
                                    .put("unreadCount", chat.unreadCount);
                        }
                    });

                    item.put("accountName", item.getString("accountName", item.getString("telegramId")))
                            .put("chatName", item.getString("chatName", item.getString("chatId")))
                            .put("chatType", item.getString("chatType", "unknown"))
                            .put("chatAvatar", item.getString("chatAvatar", ""));
                    return item;
                })
                .sorted(Comparator.comparing((JsonObject item) -> item.getString("accountName", ""))
                        .thenComparing(item -> item.getString("chatName", "")))
                .toList();

        ctx.json(new JsonArray(overviewItems));
    }

    private String accountDisplayName(TelegramVerticle telegramVerticle) {
        if (telegramVerticle.telegramRecord == null) {
            return Convert.toStr(telegramVerticle.getId());
        }
        return StrUtil.blankToDefault(telegramVerticle.telegramRecord.firstName(), Convert.toStr(telegramVerticle.getId()));
    }

    private String minithumbnail(TdApi.Chat chat) {
        byte[] data = (byte[]) BeanUtil.getProperty(chat, "photo.minithumbnail.data");
        return data == null ? "" : Base64.encode(data);
    }

    private void handleTelegramFiles(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticleByPath(ctx);
        if (telegramVerticle == null) {
            return;
        }
        String chatId = ctx.pathParam("chatId");
        if (StrUtil.isBlank(chatId)) {
            ctx.fail(400);
            return;
        }
        String link = URLUtil.decode(ctx.queryParams().get("link"));
        if (StrUtil.isNotBlank(link)) {
            telegramVerticle.parseLink(link)
                    .onSuccess(ctx::json)
                    .onFailure(ctx::fail);
            return;
        }

        Map<String, String> filter = new HashMap<>();
        ctx.request().params().forEach(filter::put);
        filter.put("search", URLUtil.decode(filter.get("search")));

        telegramVerticle.getChatFiles(Convert.toLong(chatId), filter)
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleTelegramFilesCount(RoutingContext ctx) {
        boolean offline = Convert.toBool(ctx.queryParams().get("offline"), false);
        Long telegramId = Convert.toLong(ctx.pathParam("telegramId"), -1L);
        Long chatId = Convert.toLong(ctx.pathParam("chatId"), -1L);
        if (offline) {
            if (Convert.toBool(ctx.queryParams().get("seedOnly"), false)) {
                Map<String, String> filter = new HashMap<>();
                ctx.queryParams().forEach(entry -> filter.put(entry.getKey(), entry.getValue()));
                DataVerticle.torrentRepository.countSeedOnlyWithType(filter)
                        .onSuccess(ctx::json)
                        .onFailure(ctx::fail);
                return;
            }
            DataVerticle.fileRepository.countWithType(telegramId, chatId)
                    .onSuccess(ctx::json)
                    .onFailure(ctx::fail);
            return;
        }

        TelegramVerticle telegramVerticle = getTelegramVerticleByPath(ctx);
        if (telegramVerticle == null) {
            return;
        }
        telegramVerticle.getChatFilesCount(chatId)
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleTelegramDownloadStatistics(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticleByPath(ctx);
        if (telegramVerticle == null) {
            return;
        }

        String type = ctx.request().getParam("type");
        String timeRange = ctx.request().getParam("timeRange");
        (Objects.equals(type, "phase") ? telegramVerticle.getDownloadStatisticsByPhase(Convert.toInt(timeRange, 1)) :
                telegramVerticle.getDownloadStatistics())
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleTelegramChange(RoutingContext ctx) {
        String sessionId = ctx.session().id();
        String telegramId = ctx.request().getParam("telegramId");
        if (handleTelegramChange(sessionId, telegramId)) {
            ctx.end();
        } else {
            ctx.fail(400);
        }
    }

    private boolean handleTelegramChange(String sessionId, String telegramId) {
        if (StrUtil.isBlank(telegramId)) {
            sessionTelegramVerticles.remove(sessionId);
            return true;
        }
        Optional<TelegramVerticle> optionalTelegramVerticle = TelegramVerticles.get(telegramId);
        if (optionalTelegramVerticle.isEmpty()) {
            return false;
        }
        sessionTelegramVerticles.put(sessionId, optionalTelegramVerticle.get());
        return true;
    }

    private void handleTelegramToggleProxy(RoutingContext ctx) {
        String telegramId = ctx.request().getParam("telegramId");
        TelegramVerticles.get(telegramId)
                .ifPresentOrElse(telegramVerticle ->
                        telegramVerticle.toggleProxy(ctx.body().asJsonObject())
                                .onSuccess(r -> ctx.json(JsonObject.of("proxy", r)))
                                .onFailure(ctx::fail), () -> ctx.fail(404));
    }

    private void handleTelegramPing(RoutingContext ctx) {
        String telegramId = ctx.pathParam("telegramId");
        if (StrUtil.isBlank(telegramId)) {
            ctx.fail(400);
            return;
        }
        TelegramVerticles.get(telegramId)
                .ifPresentOrElse(telegramVerticle ->
                        telegramVerticle.ping()
                                .onSuccess(r -> ctx.json(JsonObject.of("ping", r)))
                                .onFailure(ctx::fail), () -> ctx.fail(404)
                );
    }

    private void handleTelegramTestNetwork(RoutingContext ctx) {
        String telegramId = ctx.pathParam("telegramId");
        if (StrUtil.isBlank(telegramId)) {
            ctx.fail(400);
            return;
        }
        TelegramVerticles.get(telegramId)
                .ifPresentOrElse(telegramVerticle ->
                                telegramVerticle.client.execute(new TdApi.TestNetwork(), 10000, vertx)
                                        .onComplete(r ->
                                                ctx.json(JsonObject.of("success", r.succeeded()))),
                        () -> ctx.fail(404)
                );
    }

    private void handleTelegramApiMethods(RoutingContext ctx) {
        Map<String, Class<TdApi.Function<?>>> functions = TdApiHelp.getFunctions();
        ctx.json(JsonObject.of("methods", functions.keySet()));
    }

    private void handleTelegramApiMethodParameters(RoutingContext ctx) {
        String method = ctx.pathParam("method");
        ctx.json(JsonObject.of("parameters", TdApiHelp.getFunction(method, null)));
    }

    private void handleTelegramApi(RoutingContext ctx) {
        String method = ctx.pathParam("method");
        if (method == null) {
            ctx.fail(400);
            return;
        }
        TelegramVerticle telegramVerticle = getTelegramVerticleBySession(ctx);
        if (telegramVerticle == null) {
            return;
        }
        JsonObject params = ctx.body().asJsonObject();
        telegramVerticle.execute(method, params == null ? null : params.getMap())
                .onSuccess(code -> ctx.json(JsonObject.of("code", code)))
                .onFailure(ctx::fail);
    }

    private void handleFilePreview(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticleByPath(ctx);
        if (telegramVerticle == null) {
            return;
        }
        String uniqueId = ctx.pathParam("uniqueId");
        if (StrUtil.isBlank(uniqueId)) {
            ctx.fail(404);
            return;
        }

        telegramVerticle.loadPreview(uniqueId)
                .onSuccess(tuple -> {
                    String mimeType = tuple.v2;
                    if (StrUtil.isBlank(mimeType)) {
                        mimeType = FileUtil.getMimeType(tuple.v1);
                    }

                    try {
                        Path allowedFile = resolveAllowedFile(tuple.v1);
                        fileRouteHandler.handle(ctx, allowedFile.toString(), mimeType);
                    } catch (IllegalArgumentException exception) {
                        respondJson(ctx, 403, "FILE_PATH_NOT_ALLOWED", "File is outside an allowed root");
                    }
                })
                .onFailure(ctx::fail);
    }

    private static Path resolveAllowedFile(String rawPath) {
        if (StrUtil.isBlank(rawPath)) {
            throw new IllegalArgumentException("File path is missing");
        }
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of(Config.TELEGRAM_ROOT));
        Path sharedRoot = Config.shareConfiguration().sharedRoot();
        if (Files.isDirectory(sharedRoot)) {
            roots.add(sharedRoot);
        }
        return new SafePathResolver(roots).requireAllowedRegularFile(Path.of(rawPath));
    }

    private void handleFileStartDownload(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(ctx.pathParam("telegramId"));

        JsonObject jsonObject = ctx.body().asJsonObject();
        Long chatId = jsonObject.getLong("chatId");
        Long messageId = jsonObject.getLong("messageId");
        Integer fileId = jsonObject.getInteger("fileId");
        if (chatId == null || messageId == null || fileId == null) {
            ctx.fail(400);
            return;
        }

        telegramVerticle.startDownload(chatId, messageId, fileId)
                .onSuccess(ctx::json).onFailure(ctx::fail);
    }

    private void handleFileCancelDownload(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(ctx.pathParam("telegramId"));

        JsonObject jsonObject = ctx.body().asJsonObject();
        Integer fileId = jsonObject.getInteger("fileId");
        if (fileId == null) {
            ctx.fail(400);
            return;
        }

        telegramVerticle.cancelDownload(fileId).onSuccess(_ -> ctx.end()).onFailure(ctx::fail);
    }

    private void handleFileTogglePauseDownload(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(ctx.pathParam("telegramId"));

        JsonObject jsonObject = ctx.body().asJsonObject();
        Integer fileId = jsonObject.getInteger("fileId");
        Boolean isPaused = jsonObject.getBoolean("isPaused");
        if (fileId == null || isPaused == null) {
            ctx.fail(400);
            return;
        }

        telegramVerticle.togglePauseDownload(fileId, isPaused)
                .onSuccess(_ -> ctx.end()).onFailure(ctx::fail);
    }

    private void handleFileRemove(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(ctx.pathParam("telegramId"));

        JsonObject jsonObject = ctx.body().asJsonObject();
        Integer fileId = jsonObject.getInteger("fileId");
        String uniqueId = jsonObject.getString("uniqueId");
        if (fileId == null && StrUtil.isBlank(uniqueId)) {
            ctx.fail(400);
            return;
        }

        telegramVerticle.removeFile(fileId, uniqueId)
                .onSuccess(_ -> ctx.end())
                .onFailure(ctx::fail);
    }

    private void handleFileStartDownloadMultiple(RoutingContext ctx) {
        handleFileControlMultiple(ctx, (telegramVerticle, file) -> {
            Long chatId = file.getLong("chatId");
            Long messageId = file.getLong("messageId");
            Integer fileId = file.getInteger("fileId");
            if (chatId == null || messageId == null || fileId == null) {
                return Future.failedFuture("Invalid parameters");
            }
            return telegramVerticle.startDownload(chatId, messageId, fileId);
        }, null);
    }

    private void handleFileCancelDownloadMultiple(RoutingContext ctx) {
        handleFileControlMultiple(ctx, (telegramVerticle, file) -> {
            Integer fileId = file.getInteger("fileId");
            if (fileId == null) {
                return Future.failedFuture("Invalid parameters");
            }
            return telegramVerticle.cancelDownload(fileId);
        }, "CANCEL_V1");
    }

    private void handleFileTogglePauseDownloadMultiple(RoutingContext ctx) {
        JsonObject jsonObject = ctx.body().asJsonObject();
        Boolean isPaused = jsonObject.getBoolean("isPaused");
        if (isPaused == null) {
            ctx.fail(400);
            return;
        }

        handleFileControlMultiple(ctx, (telegramVerticle, file) -> {
            Integer fileId = file.getInteger("fileId");
            if (fileId == null) {
                return Future.failedFuture("Invalid parameters");
            }
            return telegramVerticle.togglePauseDownload(fileId, isPaused);
        }, isPaused ? "PAUSE_V1" : "RESUME_V1");
    }

    private void handleFileSetUploadLimitMultiple(RoutingContext ctx) {
        JsonObject body = ctx.body().asJsonObject();
        Long uploadLimitBytesPerSecond = Convert.toLong(
                body == null ? null : body.getValue("uploadLimitBytesPerSecond")
        );
        if (uploadLimitBytesPerSecond == null || uploadLimitBytesPerSecond < 0) {
            ctx.fail(400);
            return;
        }
        handleFileControlMultiple(
                ctx,
                (_, _) -> Future.failedFuture("Upload limit is only supported for seed resources"),
                "SET_UPLOAD_LIMIT_V1",
                uploadLimitBytesPerSecond
        );
    }

    private void handleFileRemoveMultiple(RoutingContext ctx) {
        handleFileMultiple(ctx, (telegramVerticle, file) -> {
            Integer fileId = file.getInteger("fileId");
            String uniqueId = file.getString("uniqueId");
            if (fileId == null && StrUtil.isBlank(uniqueId)) {
                return Future.failedFuture("Invalid parameters");
            }
            return telegramVerticle.removeFile(fileId, uniqueId);
        });
    }

    private void handleFileTagsUpdateMultiple(RoutingContext ctx) {
        JsonObject jsonObject = ctx.body().asJsonObject();
        String tags = jsonObject.getString("tags");
        if (StrUtil.isBlank(tags)) {
            ctx.fail(400);
            return;
        }
        handleFileMultiple(ctx, (_, file) -> {
            String uniqueId = file.getString("uniqueId");
            if (StrUtil.isBlank(uniqueId)) {
                return Future.failedFuture("Invalid parameters");
            }
            return DataVerticle.fileRepository.updateTags(uniqueId, tags);
        });
    }

    private void handleFileMultiple(RoutingContext ctx, Function2<TelegramVerticle, JsonObject, Future<?>> handler) {
        JsonObject jsonObject = ctx.body().asJsonObject();
        JsonArray files = jsonObject.getJsonArray("files");
        if (CollUtil.isEmpty(files)) {
            ctx.fail(400);
            return;
        }
        Map<Long, List<Object>> groupingByTelegramId = files.stream()
                .collect(Collectors.groupingBy(f -> ((JsonObject) f).getLong("telegramId")));

        Future.all(groupingByTelegramId.entrySet()
                        .stream()
                        .flatMap(entry -> {
                            TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(entry.getKey());

                            return entry.getValue().stream()
                                    .map(f -> {
                                        JsonObject file = (JsonObject) f;
                                        return handler.apply(telegramVerticle, file);
                                    });
                        })
                        .toList()
                )
                .onSuccess(ctx::json).onFailure(r -> {
                    log.error(r, "Failed to handle multiple files: %s".formatted(r.getMessage()));
                    ctx.response()
                            .setStatusCode(400)
                            .end(JsonObject.of("error", "Part of the files failed to process: %s".formatted(r.getMessage())).encode());
                });
    }

    private void handleFileControlMultiple(
            RoutingContext ctx,
            Function2<TelegramVerticle, JsonObject, Future<?>> telegramHandler,
            String seedControlType
    ) {
        handleFileControlMultiple(ctx, telegramHandler, seedControlType, 0);
    }

    private void handleFileControlMultiple(
            RoutingContext ctx,
            Function2<TelegramVerticle, JsonObject, Future<?>> telegramHandler,
            String seedControlType,
            long uploadLimitBytesPerSecond
    ) {
        JsonObject body = ctx.body().asJsonObject();
        JsonArray files = body == null ? null : body.getJsonArray("files");
        if (CollUtil.isEmpty(files)) {
            ctx.fail(400);
            return;
        }
        Future.all(files.stream()
                        .filter(JsonObject.class::isInstance)
                        .map(JsonObject.class::cast)
                        .map(file -> {
                            Long telegramId = file.getLong("telegramId");
                            if (telegramId != null && telegramId != 0) {
                                return telegramHandler.apply(TelegramVerticles.getOrElseThrow(telegramId), file);
                            }
                            if (unifiedFileDownloadService == null) {
                                return Future.failedFuture("Seed control is unavailable");
                            }
                            String uniqueId = file.getString("uniqueId", "");
                            if (!uniqueId.startsWith("seed:")) {
                                return Future.failedFuture("Seed resource is invalid");
                            }
                            String resourceId = uniqueId.substring("seed:".length());
                            return seedControlType == null
                                    ? unifiedFileDownloadService.startSeedResource(resourceId)
                                    : unifiedFileDownloadService.controlSeedResource(
                                            resourceId,
                                            seedControlType,
                                            uploadLimitBytesPerSecond
                                    );
                        })
                        .toList())
                .onSuccess(ctx::json)
                .onFailure(failure -> {
                    log.error(failure, "Failed to control multiple files: {}", failure.getMessage());
                    ctx.response().setStatusCode(400).end(JsonObject.of(
                            "error", "Part of the files failed to process: " + failure.getMessage()
                    ).encode());
                });
    }

    private void handleAutoSettingsUpdate(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(ctx.pathParam("telegramId"));

        String chatId = ctx.request().getParam("chatId");
        if (StrUtil.isBlank(chatId)) {
            ctx.fail(400);
            return;
        }
        JsonObject params = ctx.body().asJsonObject();
        telegramVerticle.updateAutoSettings(Convert.toLong(chatId), params)
                .onSuccess(_ -> ctx.end())
                .onFailure(ctx::fail);
    }

    private void handleFilesCount(RoutingContext ctx) {
        DataVerticle.fileRepository.getDownloadStatistics()
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleFiles(RoutingContext ctx) {
        Map<String, String> filter = new HashMap<>();
        ctx.request().params().forEach(filter::put);
        filter.put("search", URLUtil.decode(filter.get("search")));

        FileRecordRetriever.getFiles(0, filter)
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleFileTagsUpdate(RoutingContext ctx) {
        String uniqueId = ctx.pathParam("uniqueId");
        if (StrUtil.isBlank(uniqueId)) {
            ctx.fail(400);
            return;
        }

        JsonObject params = ctx.body().asJsonObject();
        String tags = params.getString("tags");
        DataVerticle.fileRepository.updateTags(uniqueId, tags)
                .onSuccess(_ -> ctx.end())
                .onFailure(ctx::fail);
    }

    private TelegramVerticle getTelegramVerticleBySession(RoutingContext ctx) {
        String sessionId = ctx.session().id();
        TelegramVerticle telegramVerticle = sessionTelegramVerticles.get(sessionId);
        if (telegramVerticle == null) {
            ctx.response().setStatusCode(400)
                    .end(JsonObject.of("error", "Your session not link any telegram!").encode());
            return null;
        }
        return telegramVerticle;
    }

    private TelegramVerticle getTelegramVerticleByPath(RoutingContext ctx) {
        String telegramId = ctx.pathParam("telegramId");
        if (StrUtil.isBlank(telegramId)) {
            ctx.fail(400);
            return null;
        }
        Optional<TelegramVerticle> telegramVerticleOptional = TelegramVerticles.get(telegramId);
        if (telegramVerticleOptional.isEmpty()) {
            ctx.fail(404);
            return null;
        }
        return telegramVerticleOptional.get();
    }
}
