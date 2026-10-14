package notes;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * REST API for a multi-team note-taking service.
 * Config (env): PORT (8080), DATA_FILE (data/notes.json), ADMIN_TOKEN (optional; gates user creation).
 */
public final class Server {
    private final Store store;
    private final String adminToken;

    Server(Store store, String adminToken) {
        this.store = store;
        this.adminToken = adminToken;
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Path data = Path.of(System.getenv().getOrDefault("DATA_FILE", "data/notes.json"));
        Server app = new Server(new Store(data), System.getenv("ADMIN_TOKEN"));

        HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
        http.createContext("/", app::handle);
        http.setExecutor(Executors.newFixedThreadPool(32));
        http.start();
        System.out.println("Notes service listening on http://localhost:" + port);
    }

    // ---------- dispatch ----------
    void handle(HttpExchange ex) throws IOException {
        try {
            String[] seg = Arrays.stream(ex.getRequestURI().getPath().split("/"))
                    .filter(s -> !s.isEmpty()).toArray(String[]::new);
            route(ex, ex.getRequestMethod(), seg);
        } catch (ApiException e) {
            send(ex, e.status, Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            send(ex, 400, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, Map.of("error", "Internal server error"));
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex, String m, String[] s) throws IOException {
        if (s.length == 1 && s[0].equals("health") && m.equals("GET")) {
            send(ex, 200, Map.of("status", "ok"));
            return;
        }
        if (s.length < 2 || !s[0].equals("api")) throw new ApiException(404, "Not found");

        // Registration is the only unauthenticated endpoint.
        if (s.length == 2 && s[1].equals("users") && m.equals("POST")) {
            if (adminToken != null && !adminToken.equals(ex.getRequestHeaders().getFirst("X-Admin-Token")))
                throw new ApiException(403, "Admin token required to create users");
            String name = reqStr(body(ex), "name", 100);
            Store.Issued i = store.createUser(name);
            Map<String, Object> out = new LinkedHashMap<>(i.user().json());
            out.put("apiKey", i.apiKey()); // shown once; only a hash is stored
            send(ex, 201, out);
            return;
        }

        User me = authenticate(ex);

        if (s.length == 2 && s[1].equals("me") && m.equals("GET")) {
            send(ex, 200, me.json());
            return;
        }

        if (s[1].equals("teams")) {
            if (s.length == 2 && m.equals("GET")) { send(ex, 200, store.teamsFor(me.id())); return; }
            if (s.length == 2 && m.equals("POST")) {
                Team t = store.createTeam(reqStr(body(ex), "name", 100), me.id());
                Map<String, Object> out = new LinkedHashMap<>(t.json());
                out.put("role", Role.OWNER);
                send(ex, 201, out);
                return;
            }
            if (s.length >= 4) {
                String teamId = s[2];
                Role role = store.roleOf(teamId, me.id())
                        .orElseThrow(() -> new ApiException(404, "Team not found")); // don't leak existence
                if (s[3].equals("members")) { members(ex, m, s, teamId, role); return; }
                if (s[3].equals("notes")) { notes(ex, m, s, teamId, me, role); return; }
            }
        }
        throw new ApiException(404, "Not found");
    }

    // ---------- members ----------
    private void members(HttpExchange ex, String m, String[] s, String teamId, Role role) throws IOException {
        if (s.length == 4 && m.equals("GET")) { send(ex, 200, store.membersOf(teamId)); return; }
        if (role != Role.OWNER) throw new ApiException(403, "Only owners can manage members");
        if (s.length == 4 && m.equals("POST")) {
            Map<String, Object> b = body(ex);
            String userId = reqStr(b, "userId", 64);
            store.user(userId).orElseThrow(() -> new ApiException(404, "User not found"));
            store.setMember(teamId, userId, parseRole(reqStr(b, "role", 16)));
            send(ex, 200, store.membersOf(teamId));
            return;
        }
        if (s.length == 5 && m.equals("DELETE")) {
            store.removeMember(teamId, s[4]);
            send(ex, 204, null);
            return;
        }
        throw new ApiException(404, "Not found");
    }

    // ---------- notes ----------
    private void notes(HttpExchange ex, String m, String[] s, String teamId, User me, Role role) throws IOException {
        if (s.length == 4 && m.equals("GET")) {
            Map<String, String> q = query(ex.getRequestURI());
            List<Note> all = store.search(teamId, q.get("q"), q.get("tag"));
            int limit = Math.min(intParam(q, "limit", 50), 200);
            int offset = intParam(q, "offset", 0);
            if (limit < 1 || offset < 0) throw new ApiException(400, "Invalid limit/offset");
            List<Object> page = all.stream().skip(offset).limit(limit).map(n -> (Object) n.json()).toList();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", page);
            out.put("total", all.size());
            out.put("limit", limit);
            out.put("offset", offset);
            send(ex, 200, out);
            return;
        }
        if (s.length == 4 && m.equals("POST")) {
            requireWrite(role);
            Map<String, Object> b = body(ex);
            Note n = store.createNote(teamId, me.id(), reqStr(b, "title", 200),
                    optStr(b, "body", 100_000, ""), tags(b.get("tags")));
            send(ex, 201, n.json());
            return;
        }
        if (s.length == 5) {
            String noteId = s[4];
            switch (m) {
                case "GET" -> send(ex, 200, store.note(teamId, noteId)
                        .orElseThrow(() -> new ApiException(404, "Note not found")).json());
                case "PUT" -> {
                    requireWrite(role);
                    Map<String, Object> b = body(ex);
                    Long version = null;
                    if (b.containsKey("version")) {
                        if (!(b.get("version") instanceof Number n)) throw new ApiException(400, "version must be a number");
                        version = n.longValue();
                    }
                    Note updated = store.updateNote(teamId, noteId,
                            b.containsKey("title") ? reqStr(b, "title", 200) : null,
                            b.containsKey("body") ? optStr(b, "body", 100_000, "") : null,
                            b.containsKey("tags") ? tags(b.get("tags")) : null,
                            version);
                    send(ex, 200, updated.json());
                }
                case "DELETE" -> {
                    requireWrite(role);
                    store.deleteNote(teamId, noteId);
                    send(ex, 204, null);
                }
                default -> throw new ApiException(405, "Method not allowed");
            }
            return;
        }
        throw new ApiException(404, "Not found");
    }

    // ---------- helpers ----------
    private User authenticate(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Bearer ")) throw new ApiException(401, "Missing bearer token");
        return store.authenticate(h.substring(7).trim()).orElseThrow(() -> new ApiException(401, "Invalid token"));
    }

    private static void requireWrite(Role r) {
        if (!r.canWrite()) throw new ApiException(403, "Viewers cannot modify notes");
    }

    private static Role parseRole(String s) {
        try {
            return Role.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "role must be OWNER, EDITOR or VIEWER");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(1_000_001);
        if (b.length > 1_000_000) throw new ApiException(413, "Body too large");
        if (b.length == 0) throw new ApiException(400, "JSON body required");
        Object o;
        try {
            o = Json.parse(new String(b, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new ApiException(400, "Invalid JSON");
        }
        if (!(o instanceof Map<?, ?>)) throw new ApiException(400, "JSON object expected");
        return (Map<String, Object>) o;
    }

    private static String reqStr(Map<String, Object> b, String key, int max) {
        Object v = b.get(key);
        if (!(v instanceof String s) || s.isBlank()) throw new ApiException(400, key + " is required");
        if (s.length() > max) throw new ApiException(400, key + " too long (max " + max + ")");
        return s.trim();
    }

    private static String optStr(Map<String, Object> b, String key, int max, String dflt) {
        Object v = b.get(key);
        if (v == null) return dflt;
        if (!(v instanceof String s)) throw new ApiException(400, key + " must be a string");
        if (s.length() > max) throw new ApiException(400, key + " too long (max " + max + ")");
        return s;
    }

    private static List<String> tags(Object o) {
        if (o == null) return List.of();
        if (!(o instanceof List<?> l) || l.size() > 20) throw new ApiException(400, "tags must be an array of up to 20 strings");
        Set<String> out = new LinkedHashSet<>();
        for (Object x : l) {
            if (!(x instanceof String t) || t.isBlank() || t.length() > 40)
                throw new ApiException(400, "each tag must be a non-empty string up to 40 chars");
            out.add(t.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(out);
    }

    private static Map<String, String> query(URI u) {
        Map<String, String> m = new HashMap<>();
        String q = u.getRawQuery();
        if (q == null) return m;
        for (String p : q.split("&")) {
            int i = p.indexOf('=');
            String k = i < 0 ? p : p.substring(0, i);
            String v = i < 0 ? "" : p.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private static int intParam(Map<String, String> q, String k, int dflt) {
        try {
            return q.containsKey(k) ? Integer.parseInt(q.get(k)) : dflt;
        } catch (NumberFormatException e) {
            throw new ApiException(400, k + " must be an integer");
        }
    }

    private static void send(HttpExchange ex, int status, Object payload) throws IOException {
        if (payload == null) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        byte[] out = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
    }
}
