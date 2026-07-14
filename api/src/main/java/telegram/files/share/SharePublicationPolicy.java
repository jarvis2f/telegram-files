package telegram.files.share;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import telegram.files.repository.FileRecord;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record SharePublicationPolicy(
        String defaultDecision,
        String defaultCategoryId,
        List<Category> categories,
        List<Rule> shareRules
) {

    public record Category(String id, String label, Set<String> defaultForFileTypes) {
    }

    public record Rule(String id, String decision, String reason, Match match) {
    }

    public record Match(
            Set<String> fileTypes,
            Set<String> mimeTypes,
            Set<String> mimeTypePrefixes,
            Long minFileSizeBytes,
            Long maxFileSizeBytes
    ) {
    }

    public static SharePublicationPolicy defaults() {
        return new SharePublicationPolicy(
                "ALLOW",
                "file",
                List.of(
                        new Category("file", "File", Set.of("file")),
                        new Category("video", "Video", Set.of("video")),
                        new Category("audio", "Audio", Set.of("audio")),
                        new Category("archive", "Archive", Set.of()),
                        new Category("document", "Document", Set.of()),
                        new Category("other", "Other", Set.of())
                ),
                List.of(
                        new Rule(
                                "deny-preview-types",
                                "DENY",
                                "File type is not shareable",
                                new Match(Set.of("thumbnail", "photo"), Set.of(), Set.of(), null, null)
                        ),
                        new Rule(
                                "deny-small-files",
                                "DENY",
                                "File is smaller than the minimum share size",
                                new Match(Set.of(), Set.of(), Set.of(), null, 50L * 1024 * 1024 - 1)
                        )
                )
        );
    }

    public static SharePublicationPolicy from(JsonObject json) {
        if (json == null || json.isEmpty()) {
            return defaults();
        }
        String defaultDecision = decision(json.getString("defaultDecision", "ALLOW"));
        JsonArray rawCategories = json.getJsonArray("categories");
        List<Category> categories = rawCategories == null
                ? defaults().categories()
                : rawCategories.stream().map(JsonObject.class::cast).map(SharePublicationPolicy::category).toList();
        if (categories.isEmpty()) {
            throw new IllegalArgumentException("categories cannot be empty");
        }
        String defaultCategoryId = normalize(json.getString("defaultCategoryId", categories.getFirst().id()));
        if (categories.stream().noneMatch(category -> category.id().equals(defaultCategoryId))) {
            throw new IllegalArgumentException("defaultCategoryId must reference an allowed category");
        }
        JsonArray rawRules = json.getJsonArray("shareRules", new JsonArray());
        List<Rule> rules = rawRules.stream().map(JsonObject.class::cast).map(SharePublicationPolicy::rule).toList();
        return new SharePublicationPolicy(defaultDecision, defaultCategoryId, categories, rules);
    }

    public JsonObject toJson() {
        return new JsonObject()
                .put("defaultDecision", defaultDecision)
                .put("defaultCategoryId", defaultCategoryId)
                .put("categories", new JsonArray(categories.stream().map(category -> new JsonObject()
                        .put("id", category.id())
                        .put("label", category.label())
                        .put("defaultForFileTypes", new JsonArray(category.defaultForFileTypes().stream().toList()))
                ).toList()))
                .put("shareRules", new JsonArray(shareRules.stream().map(rule -> new JsonObject()
                        .put("id", rule.id())
                        .put("decision", rule.decision())
                        .put("reason", rule.reason())
                        .put("match", matchJson(rule.match()))
                ).toList()));
    }

    public void requireShareable(FileRecord file) {
        if (file == null) {
            throw new IllegalArgumentException("Telegram file was not found");
        }
        for (Rule rule : shareRules) {
            if (matches(file, rule.match())) {
                if ("DENY".equals(rule.decision())) {
                    throw new IllegalArgumentException(rule.reason());
                }
                return;
            }
        }
        if ("DENY".equals(defaultDecision)) {
            throw new IllegalArgumentException("File is not shareable");
        }
    }

    public String categoryFor(FileRecord file, String requestedCategory) {
        if (requestedCategory != null && !requestedCategory.isBlank()) {
            String normalized = normalize(requestedCategory);
            requireAllowedCategory(normalized);
            return normalized;
        }
        String type = normalize(file.type());
        return categories.stream()
                .filter(category -> category.defaultForFileTypes().contains(type))
                .map(Category::id)
                .findFirst()
                .orElse(defaultCategoryId);
    }

    public List<String> categoryIds() {
        return categories.stream().map(Category::id).toList();
    }

    private static Category category(JsonObject json) {
        String id = normalize(required(json, "id"));
        return new Category(
                id,
                json.getString("label", id).strip(),
                strings(json.getJsonArray("defaultForFileTypes", new JsonArray()))
        );
    }

    private static Rule rule(JsonObject json) {
        return new Rule(
                normalize(required(json, "id")),
                decision(required(json, "decision")),
                json.getString("reason", required(json, "id")).strip(),
                match(json.getJsonObject("match", new JsonObject()))
        );
    }

    private static Match match(JsonObject json) {
        return new Match(
                strings(json.getJsonArray("fileTypes", new JsonArray())),
                strings(json.getJsonArray("mimeTypes", new JsonArray())),
                strings(json.getJsonArray("mimeTypePrefixes", new JsonArray())),
                json.getLong("minFileSizeBytes"),
                json.getLong("maxFileSizeBytes")
        );
    }

    private static JsonObject matchJson(Match match) {
        JsonObject json = new JsonObject()
                .put("fileTypes", new JsonArray(match.fileTypes().stream().toList()))
                .put("mimeTypes", new JsonArray(match.mimeTypes().stream().toList()))
                .put("mimeTypePrefixes", new JsonArray(match.mimeTypePrefixes().stream().toList()));
        if (match.minFileSizeBytes() != null) {
            json.put("minFileSizeBytes", match.minFileSizeBytes());
        }
        if (match.maxFileSizeBytes() != null) {
            json.put("maxFileSizeBytes", match.maxFileSizeBytes());
        }
        return json;
    }

    private boolean matches(FileRecord file, Match match) {
        String type = normalize(file.type());
        String mimeType = normalize(file.mimeType());
        return (match.fileTypes().isEmpty() || match.fileTypes().contains(type))
               && (match.mimeTypes().isEmpty() || match.mimeTypes().contains(mimeType))
               && (match.mimeTypePrefixes().isEmpty()
                   || match.mimeTypePrefixes().stream().anyMatch(mimeType::startsWith))
               && (match.minFileSizeBytes() == null || file.size() >= match.minFileSizeBytes())
               && (match.maxFileSizeBytes() == null || file.size() <= match.maxFileSizeBytes());
    }

    private void requireAllowedCategory(String categoryId) {
        if (categories.stream().noneMatch(category -> category.id().equals(categoryId))) {
            throw new IllegalArgumentException("category is not allowed");
        }
    }

    private static Set<String> strings(JsonArray input) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (int i = 0; i < input.size(); i++) {
            Object value = input.getValue(i);
            if (!(value instanceof String raw) || raw.isBlank()) {
                throw new IllegalArgumentException("Policy arrays must contain non-empty strings");
            }
            values.add(normalize(raw));
        }
        return values;
    }

    private static String decision(String raw) {
        String value = raw.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("ALLOW", "DENY").contains(value)) {
            throw new IllegalArgumentException("decision is invalid");
        }
        return value;
    }

    private static String required(JsonObject json, String field) {
        String value = json.getString(field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.strip().toLowerCase(Locale.ROOT);
    }
}
