package telegram.files.share;

import io.vertx.core.Future;

import java.util.Map;
import java.util.Objects;

public final class NodeConfigurationService {
    private final NodeIdentityService identityService;

    private final SeedCoordinatorClient client;

    public NodeConfigurationService(
            NodeIdentityService identityService,
            SeedCoordinatorClient client
    ) {
        this.identityService = Objects.requireNonNull(identityService, "identityService");
        this.client = Objects.requireNonNull(client, "client");
    }

    public Future<NodeRuntimeConfiguration> refresh() {
        return identityService.access()
                .compose(access -> client.get(
                        "/api/v1/nodes/config",
                        Map.of("Authorization", "Bearer " + access.accessToken())
                ))
                .map(NodeRuntimeConfiguration::fromJson);
    }
}
