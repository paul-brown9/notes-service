package notes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "teams")
public record Team(@Id String id, String name) {

    public Team(String name) {
        this(UUID.randomUUID().toString(), name);
    }

    public Map<String, Object> json() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", name);
        return result;
    }
}
