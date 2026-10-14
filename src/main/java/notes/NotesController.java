package notes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotesController {
    private final NotesService notesService;

    public NotesController(NotesService notesService) {
        this.notesService = notesService;
    }

    @GetMapping({"/health", "/api/health"})
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/api/users")
    public ResponseEntity<Map<String, Object>> createUser(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Admin-Token", required = false) String adminToken) {
        String configuredAdminToken = System.getenv("ADMIN_TOKEN");
        if (configuredAdminToken != null
                && !configuredAdminToken.isBlank()
                && !configuredAdminToken.equals(adminToken)) {
            throw new ApiException(403, "Admin token required to create users");
        }

        String name = ApiInput.requireString(body, "name", 100);
        NotesService.Issued issued = notesService.createUser(name);
        Map<String, Object> response = new LinkedHashMap<>(issued.user().json());
        response.put("apiKey", issued.apiKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/api/me")
    public Map<String, Object> me(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return currentUser(authorization).json();
    }

    @GetMapping("/api/teams")
    public List<Map<String, Object>> teams(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        User user = currentUser(authorization);
        return notesService.teamsFor(user.id());
    }

    @PostMapping("/api/teams")
    public ResponseEntity<Map<String, Object>> createTeam(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        User user = currentUser(authorization);
        String name = ApiInput.requireString(body, "name", 100);
        Team team = notesService.createTeam(name, user.id());

        Map<String, Object> response = new LinkedHashMap<>(team.json());
        response.put("role", Role.OWNER);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/api/teams/{teamId}/members")
    public List<Map<String, Object>> members(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String teamId) {
        User user = currentUser(authorization);
        notesService.requireTeamAccess(teamId, user.id());
        return notesService.membersOf(teamId);
    }

    @PostMapping("/api/teams/{teamId}/members")
    public List<Map<String, Object>> addMember(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String teamId,
            @RequestBody Map<String, Object> body) {
        User user = currentUser(authorization);
        requireOwner(teamId, user.id());

        String userId = ApiInput.requireString(body, "userId", 64);
        if (notesService.user(userId).isEmpty()) {
            throw new ApiException(404, "User not found");
        }

        Role role = ApiInput.parseRole(ApiInput.requireString(body, "role", 16));
        notesService.setMember(teamId, userId, role);
        return notesService.membersOf(teamId);
    }

    @DeleteMapping("/api/teams/{teamId}/members/{userId}")
    public ResponseEntity<Void> removeMember(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String teamId,
            @PathVariable String userId) {
        User user = currentUser(authorization);
        requireOwner(teamId, user.id());
        notesService.removeMember(teamId, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/teams/{teamId}/notes")
    public Map<String, Object> listNotes(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String teamId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false, defaultValue = "50") Integer limit,
            @RequestParam(required = false, defaultValue = "0") Integer offset) {
        User user = currentUser(authorization);
        notesService.requireTeamAccess(teamId, user.id());
        validatePage(limit, offset);

        List<Note> notes = notesService.search(teamId, q, tag);
        List<Map<String, Object>> items = notes.stream()
                .skip(offset)
                .limit(limit)
                .map(Note::json)
                .toList();

        return Map.of(
                "items", items,
                "total", notes.size(),
                "limit", limit,
                "offset", offset);
    }

    @PostMapping("/api/teams/{teamId}/notes")
    public ResponseEntity<Map<String, Object>> createNote(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String teamId,
            @RequestBody Map<String, Object> body) {
        User user = currentUser(authorization);
        requireWriter(teamId, user.id());

        String title = ApiInput.requireString(body, "title", 200);
        String noteBody = ApiInput.optionalString(body, "body", 100_000, "");
        List<String> tags = ApiInput.parseTags(body.get("tags"));
        Note note = notesService.createNote(teamId, user.id(), title, noteBody, tags);
        return ResponseEntity.status(HttpStatus.CREATED).body(note.json());
    }

    @GetMapping("/api/teams/{teamId}/notes/{noteId}")
    public Map<String, Object> getNote(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String teamId,
            @PathVariable String noteId) {
        User user = currentUser(authorization);
        notesService.requireTeamAccess(teamId, user.id());
        return notesService.note(teamId, noteId)
                .orElseThrow(() -> new ApiException(404, "Note not found"))
                .json();
    }

    @PutMapping("/api/teams/{teamId}/notes/{noteId}")
    public Map<String, Object> updateNote(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String teamId,
            @PathVariable String noteId,
            @RequestBody Map<String, Object> body) {
        User user = currentUser(authorization);
        requireWriter(teamId, user.id());

        String title = body.containsKey("title")
                ? ApiInput.requireString(body, "title", 200)
                : null;
        String noteBody = body.containsKey("body")
                ? ApiInput.optionalString(body, "body", 100_000, "")
                : null;
        List<String> tags = body.containsKey("tags")
                ? ApiInput.parseTags(body.get("tags"))
                : null;

        return notesService.updateNote(
                teamId,
                noteId,
                title,
                noteBody,
                tags,
                expectedVersion(body)).json();
    }

    @DeleteMapping("/api/teams/{teamId}/notes/{noteId}")
    public ResponseEntity<Void> deleteNote(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String teamId,
            @PathVariable String noteId) {
        User user = currentUser(authorization);
        requireWriter(teamId, user.id());
        notesService.deleteNote(teamId, noteId);
        return ResponseEntity.noContent().build();
    }

    private User currentUser(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ApiException(401, "Missing bearer token");
        }

        String token = authorization.substring(7).trim();
        return notesService.authenticate(token)
                .orElseThrow(() -> new ApiException(401, "Invalid token"));
    }

    private void requireOwner(String teamId, String userId) {
        Role role = requireRole(teamId, userId);
        if (role != Role.OWNER) {
            throw new ApiException(403, "Only owners can manage members");
        }
    }

    private void requireWriter(String teamId, String userId) {
        if (!requireRole(teamId, userId).canWrite()) {
            throw new ApiException(403, "Viewers cannot modify notes");
        }
    }

    private Role requireRole(String teamId, String userId) {
        return notesService.roleOf(teamId, userId)
                .orElseThrow(() -> new ApiException(404, "Team not found"));
    }

    private void validatePage(Integer limit, Integer offset) {
        if (limit == null || limit < 1 || limit > 200) {
            throw new ApiException(400, "Invalid limit");
        }
        if (offset == null || offset < 0) {
            throw new ApiException(400, "Invalid offset");
        }
    }

    private Long expectedVersion(Map<String, Object> body) {
        if (!body.containsKey("version")) {
            return null;
        }

        Object value = body.get("version");
        if (!(value instanceof Number number)) {
            throw new ApiException(400, "version must be a number");
        }
        return number.longValue();
    }
}
