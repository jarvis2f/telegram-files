package telegram.files.repository;

import io.vertx.core.Future;

public interface InstallationIdentityRepository {
    Future<InstallationIdentityRecord> getCurrent();

    Future<InstallationIdentityRecord> saveIfAbsent(InstallationIdentityRecord identity);
}
