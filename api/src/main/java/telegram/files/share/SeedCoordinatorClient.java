package telegram.files.share;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Map;

public interface SeedCoordinatorClient {

    Future<JsonObject> get(String path, Map<String, String> headers);

    default Future<JsonArray> getArray(String path, Map<String, String> headers) {
        return get(path, headers).map(response -> response.getJsonArray("items", new JsonArray()));
    }

    Future<JsonObject> post(String path, JsonObject body, Map<String, String> headers);

    Future<JsonObject> put(String path, JsonObject body, Map<String, String> headers);

    Future<JsonObject> delete(String path, Map<String, String> headers);
}
