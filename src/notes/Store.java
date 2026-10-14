package notes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/**
 * In-memory store with an atomic JSON snapshot on every write.
 * Good enough for several small teams; swap this class for a JDBC/JPA
 * implementation (Postgres) when you outgrow it. Server only talks to these methods.
 */
final class Store {
    record Issued(User user, String apiKey) {}

    private final Map<String, User> users = new LinkedHashMap<>();
    private final Map<String, String> keyIndex = new HashMap<>(); // keyHash -> userId
    private final Map<String, Team> teams = new LinkedHashMap<>();
    private final List<Membership> members = new ArrayList<>();
    private final Map<String, Note> notes = new LinkedHashMap<>();
    private final Path file;
    private final SecureRandom rng = new SecureRandom();

    Store(Path file) {
        this.file = file;
        load();
    }

    // ---- users / auth ----
    synchronized Issued createUser(String name) {
        byte[] raw = new byte[24];
        rng.nextBytes(raw);
        String apiKey = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        User u = new User(id(), name, hash(apiKey));
        users.put(u.id(), u);
        keyIndex.put(u.keyHash(), u.id());
        save();
        return new Issued(u, apiKey);
    }

    synchronized Optional<User> authenticate(String apiKey) {
        String id = keyIndex.get(hash(apiKey));
        return id == null ? Optional.empty() : Optional.ofNullable(users.get(id));
    }

    synchronized Optional<User> user(String id) { return Optional.ofNullable(users.get(id)); }

    // ---- teams / membership ----
    synchronized Team createTeam(String name, String ownerId) {
        Team t = new Team(id(), name);
        teams.put(t.id(), t);
        members.add(new Membership(t.id(), ownerId, Role.OWNER));
        save();
        return t;
    }

    synchronized List<Map<String, Object>> teamsFor(String userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Membership m : members) {
            if (!m.userId().equals(userId)) continue;
            Map<String, Object> j = new LinkedHashMap<>(teams.get(m.teamId()).json());
            j.put("role", m.role());
            out.add(j);
        }
        return out;
    }

    synchronized Optional<Role> roleOf(String teamId, String userId) {
        for (Membership m : members)
            if (m.teamId().equals(teamId) && m.userId().equals(userId)) return Optional.of(m.role());
        return Optional.empty();
    }

    synchronized List<Map<String, Object>> membersOf(String teamId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Membership m : members) {
            if (!m.teamId().equals(teamId)) continue;
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("userId", m.userId());
            j.put("name", users.get(m.userId()).name());
            j.put("role", m.role());
            out.add(j);
        }
        return out;
    }

    synchronized void setMember(String teamId, String userId, Role role) {
        Optional<Role> current = roleOf(teamId, userId);
        if (current.orElse(null) == Role.OWNER && role != Role.OWNER && ownerCount(teamId) == 1)
            throw new ApiException(409, "A team must keep at least one owner");
        members.removeIf(m -> m.teamId().equals(teamId) && m.userId().equals(userId));
        members.add(new Membership(teamId, userId, role));
        save();
    }

    synchronized void removeMember(String teamId, String userId) {
        Role r = roleOf(teamId, userId).orElseThrow(() -> new ApiException(404, "Not a member"));
        if (r == Role.OWNER && ownerCount(teamId) == 1)
            throw new ApiException(409, "A team must keep at least one owner");
        members.removeIf(m -> m.teamId().equals(teamId) && m.userId().equals(userId));
        save();
    }

    private long ownerCount(String teamId) {
        return members.stream().filter(m -> m.teamId().equals(teamId) && m.role() == Role.OWNER).count();
    }

    // ---- notes ----
    synchronized List<Note> search(String teamId, String q, String tag) {
        String needle = q == null ? null : q.toLowerCase(Locale.ROOT);
        return notes.values().stream()
                .filter(n -> n.teamId().equals(teamId))
                .filter(n -> tag == null || n.tags().contains(tag))
                .filter(n -> needle == null
                        || n.title().toLowerCase(Locale.ROOT).contains(needle)
                        || n.body().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(Note::updatedAt).reversed())
                .toList();
    }

    synchronized Optional<Note> note(String teamId, String noteId) {
        return Optional.ofNullable(notes.get(noteId)).filter(n -> n.teamId().equals(teamId));
    }

    synchronized Note createNote(String teamId, String authorId, String title, String body, List<String> tags) {
        Instant now = Instant.now();
        Note n = new Note(id(), teamId, authorId, title, body, tags, now, now, 1);
        notes.put(n.id(), n);
        save();
        return n;
    }

    /** Null title/body/tags mean "keep existing". expectedVersion enables optimistic concurrency. */
    synchronized Note updateNote(String teamId, String noteId, String title, String body,
                                 List<String> tags, Long expectedVersion) {
        Note old = note(teamId, noteId).orElseThrow(() -> new ApiException(404, "Note not found"));
        if (expectedVersion != null && expectedVersion != old.version())
            throw new ApiException(409, "Version conflict: current version is " + old.version());
        Note n = new Note(old.id(), old.teamId(), old.authorId(),
                title != null ? title : old.title(),
                body != null ? body : old.body(),
                tags != null ? tags : old.tags(),
                old.createdAt(), Instant.now(), old.version() + 1);
        notes.put(n.id(), n);
        save();
        return n;
    }

    synchronized void deleteNote(String teamId, String noteId) {
        note(teamId, noteId).orElseThrow(() -> new ApiException(404, "Note not found"));
        notes.remove(noteId);
        save();
    }

    // ---- persistence ----
    private static String id() { return UUID.randomUUID().toString(); }

    private static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void save() {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            List<Object> u = new ArrayList<>();
            for (User x : users.values()) {
                Map<String, Object> m = x.json();
                m.put("keyHash", x.keyHash());
                u.add(m);
            }
            root.put("users", u);
            root.put("teams", teams.values().stream().map(Team::json).toList());
            root.put("members", members.stream().map(m -> {
                Map<String, Object> j = new LinkedHashMap<>();
                j.put("teamId", m.teamId());
                j.put("userId", m.userId());
                j.put("role", m.role());
                return j;
            }).toList());
            root.put("notes", notes.values().stream().map(Note::json).toList());

            Path abs = file.toAbsolutePath();
            Files.createDirectories(abs.getParent());
            Path tmp = abs.resolveSibling(abs.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(root));
            Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void load() {
        try {
            if (!Files.exists(file)) return;
            Map<String, Object> root = (Map<String, Object>) Json.parse(Files.readString(file));
            for (Object o : (List<Object>) root.get("users")) {
                Map<String, Object> m = (Map<String, Object>) o;
                User x = new User((String) m.get("id"), (String) m.get("name"), (String) m.get("keyHash"));
                users.put(x.id(), x);
                keyIndex.put(x.keyHash(), x.id());
            }
            for (Object o : (List<Object>) root.get("teams")) {
                Map<String, Object> m = (Map<String, Object>) o;
                Team t = new Team((String) m.get("id"), (String) m.get("name"));
                teams.put(t.id(), t);
            }
            for (Object o : (List<Object>) root.get("members")) {
                Map<String, Object> m = (Map<String, Object>) o;
                members.add(new Membership((String) m.get("teamId"), (String) m.get("userId"),
                        Role.valueOf((String) m.get("role"))));
            }
            for (Object o : (List<Object>) root.get("notes")) {
                Map<String, Object> m = (Map<String, Object>) o;
                Note n = new Note((String) m.get("id"), (String) m.get("teamId"), (String) m.get("authorId"),
                        (String) m.get("title"), (String) m.get("body"), (List<String>) m.get("tags"),
                        Instant.parse((String) m.get("createdAt")), Instant.parse((String) m.get("updatedAt")),
                        ((Number) m.get("version")).longValue());
                notes.put(n.id(), n);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
