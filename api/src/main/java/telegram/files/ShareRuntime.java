package telegram.files;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import telegram.files.share.HttpSeedCoordinatorClient;
import telegram.files.share.InstallationIdentityService;
import telegram.files.share.LocalTorrentMetadataStore;
import telegram.files.share.NodeHeartbeatService;
import telegram.files.share.NodeConfigurationService;
import telegram.files.share.NodeIdentityService;
import telegram.files.share.NodeTaskPoller;
import telegram.files.share.PersistentShareService;
import telegram.files.share.PrivateTorrentService;
import telegram.files.share.QbittorrentClient;
import telegram.files.share.ResourcePublishingService;
import telegram.files.share.ShareConfiguration;
import telegram.files.share.ShareVerticle;
import telegram.files.share.StreamingContentHashService;
import telegram.files.share.TelegramBootstrapExecutor;
import telegram.files.share.TorrentConfiguration;
import telegram.files.share.TorrentControlExecutor;
import telegram.files.share.TorrentDownloadExecutor;
import telegram.files.share.TorrentReconciler;
import telegram.files.share.TorrentStatisticsReporter;
import telegram.files.share.TorrentViewStore;
import telegram.files.share.UnifiedFileDownloadService;
import telegram.files.share.V1TorrentService;

import java.util.concurrent.CompletionException;

final class ShareRuntime {
    private static final Log log = LogFactory.get();

    private final Vertx vertx;

    private final HttpVerticle httpVerticle;

    private final ShareConfiguration shareConfiguration;

    private final TorrentConfiguration torrentConfiguration;

    private final HttpSeedCoordinatorClient seedCoordinatorClient;

    private final NodeIdentityService nodeIdentityService;

    private final InstallationIdentityService installationIdentityService;

    private QbittorrentClient qbittorrentClient;

    private PrivateTorrentService privateTorrentService;

    private V1TorrentService torrentService;

    private LocalTorrentMetadataStore metadataStore;

    private TorrentDownloadExecutor torrentDownloadExecutor;

    private TorrentReconciler torrentReconciler;

    private TorrentStatisticsReporter torrentStatisticsReporter;

    private TorrentControlExecutor torrentControlExecutor;

    private ShareRuntime(
            Vertx vertx,
            HttpVerticle httpVerticle,
            ShareConfiguration shareConfiguration
    ) {
        this.vertx = vertx;
        this.httpVerticle = httpVerticle;
        this.shareConfiguration = shareConfiguration;
        torrentConfiguration = Config.torrentConfiguration(shareConfiguration);
        seedCoordinatorClient = new HttpSeedCoordinatorClient(shareConfiguration);
        installationIdentityService = new InstallationIdentityService(
                DataVerticle.installationIdentityRepository,
                Config.shareSecretStore()
        );
        nodeIdentityService = new NodeIdentityService(
                vertx,
                shareConfiguration,
                seedCoordinatorClient,
                DataVerticle.seedNodeIdentityRepository,
                installationIdentityService,
                Config.shareSecretStore()
        );
    }

    static Future<ShareVerticle> initialize(
            Vertx vertx,
            HttpVerticle httpVerticle,
            ShareConfiguration shareConfiguration
    ) {
        ShareRuntime runtime = new ShareRuntime(vertx, httpVerticle, shareConfiguration);
        return runtime.initializeTorrentRuntime()
                .map(_ -> runtime.createShareVerticle());
    }

    private ShareVerticle createShareVerticle() {
        NodeTaskPoller taskPoller = taskPoller();
        NodeConfigurationService nodeConfigurationService = new NodeConfigurationService(
                nodeIdentityService,
                seedCoordinatorClient
        );
        UnifiedFileDownloadService unifiedFileDownloadService = new UnifiedFileDownloadService(
                nodeIdentityService,
                seedCoordinatorClient,
                DataVerticle.torrentRepository,
                torrentControlExecutor,
                torrentStatisticsReporter
        );
        TelegramVerticles.configureUnifiedFileDownloadService(unifiedFileDownloadService);
        httpVerticle.configureUnifiedFileDownloadService(unifiedFileDownloadService);

        return new ShareVerticle(
                shareConfiguration,
                new PersistentShareService(DataVerticle.shareRepository),
                nodeIdentityService,
                heartbeatService(taskPoller, nodeConfigurationService),
                resourcePublishingService(),
                taskPoller,
                torrentReconciler,
                torrentStatisticsReporter
        );
    }

    private NodeHeartbeatService heartbeatService(
            NodeTaskPoller taskPoller,
            NodeConfigurationService nodeConfigurationService
    ) {
        return new NodeHeartbeatService(
                vertx,
                shareConfiguration,
                nodeIdentityService,
                seedCoordinatorClient,
                DataVerticle.seedNodeIdentityRepository,
                DataVerticle.nodeTaskRepository,
                torrentConfiguration,
                DataVerticle.torrentRepository,
                taskPoller,
                nodeConfigurationService,
                torrentStatisticsReporter
        );
    }

    private NodeTaskPoller taskPoller() {
        TelegramBootstrapExecutor executor = new TelegramBootstrapExecutor(
                vertx,
                DataVerticle.shareSourceRepository,
                DataVerticle.fileRepository,
                new StreamingContentHashService(vertx)
        );
        return new NodeTaskPoller(
                vertx,
                shareConfiguration,
                nodeIdentityService,
                seedCoordinatorClient,
                DataVerticle.nodeTaskRepository,
                executor,
                torrentDownloadExecutor,
                privateTorrentService,
                torrentControlExecutor,
                Config.shareSecretStore()
        );
    }

    private Future<Void> initializeTorrentRuntime() {
        if (!torrentConfiguration.enabled()) {
            return Future.succeededFuture();
        }
        qbittorrentClient = new QbittorrentClient(torrentConfiguration);
        torrentService = new V1TorrentService(vertx);
        TorrentViewStore viewStore = new TorrentViewStore(vertx, shareConfiguration.sharedRoot());
        metadataStore = new LocalTorrentMetadataStore(
                vertx, shareConfiguration.sharedRoot()
        );
        StreamingContentHashService hashService = new StreamingContentHashService(vertx);
        privateTorrentService = new PrivateTorrentService(
                vertx,
                torrentConfiguration,
                seedCoordinatorClient,
                torrentService,
                viewStore,
                metadataStore,
                qbittorrentClient,
                DataVerticle.fileRepository,
                DataVerticle.torrentRepository
        );
        torrentDownloadExecutor = new TorrentDownloadExecutor(
                vertx,
                torrentConfiguration,
                nodeIdentityService,
                torrentService,
                viewStore,
                metadataStore,
                qbittorrentClient,
                hashService,
                DataVerticle.torrentRepository
        );
        torrentControlExecutor = new TorrentControlExecutor(
                vertx,
                torrentConfiguration,
                DataVerticle.torrentRepository,
                qbittorrentClient,
                nodeIdentityService,
                metadataStore,
                torrentService
        );
        torrentReconciler = new TorrentReconciler(
                DataVerticle.torrentRepository,
                qbittorrentClient,
                metadataStore,
                torrentService,
                nodeIdentityService
        );
        torrentStatisticsReporter = new TorrentStatisticsReporter(
                nodeIdentityService,
                seedCoordinatorClient,
                DataVerticle.torrentRepository,
                DataVerticle.torrentStatisticEventRepository,
                DataVerticle.torrentUploadSessionRepository,
                qbittorrentClient,
                installationIdentityService,
                Config.SHARE_STATISTICS_ROLLOUT_PERCENT
        );
        return qbittorrentClient.healthCheck()
                .recover(failure -> {
                    Throwable rootCause = getRootCause(failure);
                    String url = torrentConfiguration.webApiUri() != null ? torrentConfiguration.webApiUri().toString() : "";
                    String message;
                    if (rootCause instanceof java.net.ConnectException) {
                        message = String.format("Failed to connect to qBittorrent Web API at %s (Connection refused). "
                                + "Please ensure qBittorrent is running and accessible.", url);
                    } else if (rootCause instanceof QbittorrentClient.TorrentClientException tce) {
                        message = String.format("qBittorrent Web API error at %s: %s", url, tce.getMessage());
                    } else {
                        message = String.format("qBittorrent health check failed at %s: %s", url, rootCause.getMessage());
                    }
                    log.error("❌ {}", message);
                    return Future.failedFuture(new IllegalStateException(message, failure));
                });
    }

    private static Throwable getRootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null && cause.getCause() != null && (cause instanceof CompletionException || cause instanceof IllegalStateException)) {
            cause = cause.getCause();
        }
        return cause;
    }

    private ResourcePublishingService resourcePublishingService() {
        return new ResourcePublishingService(
                shareConfiguration,
                nodeIdentityService,
                seedCoordinatorClient,
                DataVerticle.shareSourceRepository,
                DataVerticle.fileRepository,
                Config.shareSecretStore()
        );
    }
}
