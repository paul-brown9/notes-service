package notes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class NotesService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String USERS = "users";
    private static final String TEAMS = "teams";
    private static final String MEMBERSHIPS = "memberships";
    private static final String NOTES = "notes";

    private final MongoTemplate mongoTemplate;

    public NotesService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public record Issued(User user, String apiKey) {
    }

    public Issued createUser(String name) {
        String apiKey = generateApiKey();
        User user = new User(name, hash(apiKey));
        mongoTemplate.insert(user, USERS);
        return new Issued(user, apiKey);
    }

    public Optional<User> authenticate(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }

        String keyHash = hash(apiKey.trim());
        Query query = Query.query(Criteria.where("keyHash").is(keyHash));
        return Optional.ofNullable(mongoTemplate.findOne(query, User.class, USERS));
    }

    public Optional<User> user(String id) {
        return Optional.ofNullable(mongoTemplate.findById(id, User.class, USERS));
    }

    public Team createTeam(String name, String ownerId) {
        Team team = mongoTemplate.insert(new Team(name), TEAMS);
        mongoTemplate.insert(new Membership(team.id(), ownerId, Role.OWNER), MEMBERSHIPS);
        return team;
    }

    public List<Map<String, Object>> teamsFor(String userId) {
        Query query = Query.query(Criteria.where("userId").is(userId));
        List<Membership> memberships = mongoTemplate.find(query, Membership.class, MEMBERSHIPS);
        List<Map<String, Object>> teams = new ArrayList<>();

        for (Membership membership : memberships) {
            Team team = mongoTemplate.findById(membership.teamId(), Team.class, TEAMS);
            if (team == null) {
                continue;
            }

            Map<String, Object> result = team.json();
            result.put("role", membership.role());
            teams.add(result);
        }
        return teams;
    }

    public Team requireTeamAccess(String teamId, String userId) {
        Team team = mongoTemplate.findById(teamId, Team.class, TEAMS);
        if (team == null || !isMember(teamId, userId)) {
            throw new ApiException(404, "Team not found");
        }
        return team;
    }

    public Optional<Role> roleOf(String teamId, String userId) {
        Membership membership = mongoTemplate.findOne(
                membershipQuery(teamId, userId),
                Membership.class,
                MEMBERSHIPS);
        return Optional.ofNullable(membership).map(Membership::role);
    }

    public List<Map<String, Object>> membersOf(String teamId) {
        Query query = Query.query(Criteria.where("teamId").is(teamId));
        List<Membership> memberships = mongoTemplate.find(query, Membership.class, MEMBERSHIPS);
        List<Map<String, Object>> members = new ArrayList<>();

        for (Membership membership : memberships) {
            User user = mongoTemplate.findById(membership.userId(), User.class, USERS);
            if (user == null) {
                continue;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("userId", membership.userId());
            result.put("name", user.name());
            result.put("role", membership.role());
            members.add(result);
        }
        return members;
    }

    public void setMember(String teamId, String userId, Role role) {
        if (role == null) {
            throw new ApiException(400, "role must be OWNER, EDITOR or VIEWER");
        }

        Optional<Role> currentRole = roleOf(teamId, userId);
        boolean removingLastOwner = currentRole.orElse(null) == Role.OWNER
                && role != Role.OWNER
                && ownerCount(teamId) == 1;
        if (removingLastOwner) {
            throw new ApiException(409, "A team must keep at least one owner");
        }

        mongoTemplate.remove(membershipQuery(teamId, userId), MEMBERSHIPS);
        mongoTemplate.insert(new Membership(teamId, userId, role), MEMBERSHIPS);
    }

    public void removeMember(String teamId, String userId) {
        Membership membership = mongoTemplate.findOne(
                membershipQuery(teamId, userId),
                Membership.class,
                MEMBERSHIPS);
        if (membership == null) {
            throw new ApiException(404, "Not a member");
        }

        if (membership.role() == Role.OWNER && ownerCount(teamId) == 1) {
            throw new ApiException(409, "A team must keep at least one owner");
        }
        mongoTemplate.remove(membershipQuery(teamId, userId), MEMBERSHIPS);
    }

    public List<Note> search(String teamId, String queryText, String tag) {
        Query query = Query.query(Criteria.where("teamId").is(teamId));

        if (tag != null && !tag.isBlank()) {
            query.addCriteria(Criteria.where("tags").is(tag.toLowerCase(Locale.ROOT)));
        }
        if (queryText != null && !queryText.isBlank()) {
            String searchTerm = Pattern.quote(queryText.toLowerCase(Locale.ROOT));
            query.addCriteria(new Criteria().orOperator(
                    Criteria.where("title").regex(searchTerm, "i"),
                    Criteria.where("body").regex(searchTerm, "i")));
        }

        query.with(Sort.by(Sort.Direction.DESC, "updatedAt"));
        return mongoTemplate.find(query, Note.class, NOTES);
    }

    public Optional<Note> note(String teamId, String noteId) {
        Note note = mongoTemplate.findById(noteId, Note.class, NOTES);
        return note != null && note.teamId().equals(teamId)
                ? Optional.of(note)
                : Optional.empty();
    }

    public Note createNote(String teamId, String authorId, String title, String body, List<String> tags) {
        Instant now = Instant.now();
        Note note = new Note(teamId, authorId, title, body, tags, now, now, 1);
        return mongoTemplate.insert(note, NOTES);
    }

    public Note updateNote(
            String teamId,
            String noteId,
            String title,
            String body,
            List<String> tags,
            Long expectedVersion) {
        Note current = note(teamId, noteId)
                .orElseThrow(() -> new ApiException(404, "Note not found"));

        if (expectedVersion != null && expectedVersion != current.version()) {
            throw new ApiException(409, "Version conflict: current version is " + current.version());
        }

        Note updated = new Note(
                current.id(),
                current.teamId(),
                current.authorId(),
                title != null ? title : current.title(),
                body != null ? body : current.body(),
                tags != null ? tags : current.tags(),
                current.createdAt(),
                Instant.now(),
                current.version() + 1);
        return mongoTemplate.save(updated, NOTES);
    }

    public void deleteNote(String teamId, String noteId) {
        Note note = note(teamId, noteId)
                .orElseThrow(() -> new ApiException(404, "Note not found"));
        mongoTemplate.remove(note, NOTES);
    }

    private static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to hash value", exception);
        }
    }

    private static String generateApiKey() {
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private boolean isMember(String teamId, String userId) {
        return mongoTemplate.exists(membershipQuery(teamId, userId), MEMBERSHIPS);
    }

    private Query membershipQuery(String teamId, String userId) {
        return Query.query(Criteria.where("teamId").is(teamId).and("userId").is(userId));
    }

    private long ownerCount(String teamId) {
        Query query = Query.query(Criteria.where("teamId").is(teamId).and("role").is(Role.OWNER));
        return mongoTemplate.count(query, MEMBERSHIPS);
    }
}
