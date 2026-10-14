package notes;

import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "memberships")
@CompoundIndex(name = "team_user_idx", def = "{teamId: 1, userId: 1}")
public record Membership(
        @Id String id,
        String teamId,
        String userId,
        Role role) {

    public Membership(String teamId, String userId, Role role) {
        this(UUID.randomUUID().toString(), teamId, userId, role);
    }
}
