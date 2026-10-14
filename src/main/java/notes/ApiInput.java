package notes;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ApiInput {
    private ApiInput() {
    }

    static String requireString(Map<String, Object> body, String key, int maxLength) {
        Object value = body.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ApiException(400, key + " is required");
        }

        String trimmed = text.trim();
        if (trimmed.length() > maxLength) {
            throw new ApiException(400, key + " too long (max " + maxLength + ")");
        }
        return trimmed;
    }

    static String optionalString(
            Map<String, Object> body,
            String key,
            int maxLength,
            String fallback) {
        if (!body.containsKey(key) || body.get(key) == null) {
            return fallback;
        }

        Object value = body.get(key);
        if (!(value instanceof String text)) {
            throw new ApiException(400, key + " must be a string");
        }
        if (text.length() > maxLength) {
            throw new ApiException(400, key + " too long (max " + maxLength + ")");
        }
        return text;
    }

    static Role parseRole(String value) {
        if (value == null) {
            throw invalidRole();
        }

        try {
            return Role.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalidRole();
        }
    }

    static List<String> parseTags(Object rawTags) {
        if (rawTags == null) {
            return List.of();
        }
        if (!(rawTags instanceof List<?> tags) || tags.size() > 20) {
            throw new ApiException(400, "tags must be an array of up to 20 strings");
        }

        Set<String> uniqueTags = new LinkedHashSet<>();
        for (Object item : tags) {
            if (!(item instanceof String tag) || tag.isBlank() || tag.length() > 40) {
                throw new ApiException(400, "each tag must be a non-empty string up to 40 chars");
            }
            uniqueTags.add(tag.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(uniqueTags);
    }

    private static ApiException invalidRole() {
        return new ApiException(400, "role must be OWNER, EDITOR or VIEWER");
    }
}
