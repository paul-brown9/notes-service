package notes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "notes")
public record Note(
        @Id String id,
        String teamId,
        String authorId,
        String title,
        String body,
        List<String> tags,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public Note(
            String teamId,
            String authorId,
            String title,
            String body,
            List<String> tags,
            Instant createdAt,
            Instant updatedAt,
            long version) {
        this(UUID.randomUUID().toString(), teamId, authorId, title, body, tags, createdAt, updatedAt, version);
    }

    public Map<String, Object> json() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("teamId", teamId);
        result.put("authorId", authorId);
        result.put("title", title);
        result.put("body", body);
        result.put("tags", tags == null ? List.of() : tags);
        result.put("createdAt", createdAt == null ? null : createdAt.toString());
        result.put("updatedAt", updatedAt == null ? null : updatedAt.toString());
        result.put("version", version);
        return result;
    }
}
