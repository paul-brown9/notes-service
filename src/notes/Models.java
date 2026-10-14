package notes;

import java.time.Instant;
import java.util.*;

enum Role {
    OWNER, EDITOR, VIEWER;

    boolean canWrite() { return this != VIEWER; }
}

record User(String id, String name, String keyHash) {
    Map<String, Object> json() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        return m;
    }
}

record Team(String id, String name) {
    Map<String, Object> json() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        return m;
    }
}

record Membership(String teamId, String userId, Role role) {}

record Note(String id, String teamId, String authorId, String title, String body,
            List<String> tags, Instant createdAt, Instant updatedAt, long version) {
    Map<String, Object> json() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("teamId", teamId);
        m.put("authorId", authorId);
        m.put("title", title);
        m.put("body", body);
        m.put("tags", tags);
        m.put("createdAt", createdAt.toString());
        m.put("updatedAt", updatedAt.toString());
        m.put("version", version);
        return m;
    }
}

final class ApiException extends RuntimeException {
    final int status;

    ApiException(int status, String message) {
        super(message);
        this.status = status;
    }
}
