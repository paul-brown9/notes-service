package notes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "users")
public record User(
        @Id String id,
        String name,
        @Indexed(unique = true) String keyHash) {

    public User(String name, String keyHash) {
        this(UUID.randomUUID().toString(), name, keyHash);
    }

    public Map<String, Object> json() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", name);
        return result;
    }
}
