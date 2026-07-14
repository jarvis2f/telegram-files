package telegram.files.repository;

import io.vertx.core.Future;

public interface SeedNodeIdentityRepository {

    Future<SeedNodeIdentityRecord> getCurrent();

    Future<Void> save(SeedNodeIdentityRecord identity);

    Future<Void> updateHeartbeat(long receivedAt);

    Future<Void> updateNodeName(String nodeName, long updatedAt);

    Future<Void> clear();
}
