package notes;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class NotesControllerBehaviorTest {
    private static final String OWNER_KEY = "owner-key";
    private static final String EDITOR_KEY = "editor-key";
    private static final String VIEWER_KEY = "viewer-key";
    private static final String TEAM_ID = "team-1";
    private static final String NOTE_ID = "note-1";

    private final User owner = new User("owner-1", "Owner", "owner-hash");
    private final User editor = new User("editor-1", "Editor", "editor-hash");
    private final User viewer = new User("viewer-1", "Viewer", "viewer-hash");
    private final Instant now = Instant.parse("2025-01-01T00:00:00Z");

    private MockMvc mockMvc;

    @Mock
    private NotesService notesService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new NotesController(notesService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void rejectsMissingAndInvalidBearerTokens() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Missing bearer token"));

        when(notesService.authenticate("bad-key")).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer bad-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid token"));
    }

    @Test
    void hidesTeamsFromNonMembers() throws Exception {
        authenticate(OWNER_KEY, owner);
        when(notesService.requireTeamAccess(TEAM_ID, owner.id()))
                .thenThrow(new ApiException(404, "Team not found"));

        mockMvc.perform(get("/api/teams/{teamId}/notes", TEAM_ID)
                        .header("Authorization", bearer(OWNER_KEY)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Team not found"));
    }

    @Test
    void viewerCannotCreateUpdateOrDeleteNotes() throws Exception {
        authenticate(VIEWER_KEY, viewer);
        when(notesService.roleOf(TEAM_ID, viewer.id())).thenReturn(Optional.of(Role.VIEWER));

        mockMvc.perform(post("/api/teams/{teamId}/notes", TEAM_ID)
                        .header("Authorization", bearer(VIEWER_KEY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Blocked\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/teams/{teamId}/notes/{noteId}", TEAM_ID, NOTE_ID)
                        .header("Authorization", bearer(VIEWER_KEY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Blocked\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/teams/{teamId}/notes/{noteId}", TEAM_ID, NOTE_ID)
                        .header("Authorization", bearer(VIEWER_KEY)))
                .andExpect(status().isForbidden());

        verify(notesService, never()).createNote(any(), any(), any(), any(), any());
        verify(notesService, never()).updateNote(any(), any(), any(), any(), any(), any());
        verify(notesService, never()).deleteNote(any(), any());
    }

    @Test
    void editorCanWriteNotesButCannotManageMembers() throws Exception {
        authenticate(EDITOR_KEY, editor);
        when(notesService.roleOf(TEAM_ID, editor.id())).thenReturn(Optional.of(Role.EDITOR));
        Note note = note("Created by editor", 1);
        when(notesService.createNote(TEAM_ID, editor.id(), "Created by editor", "", List.of()))
                .thenReturn(note);
        when(notesService.updateNote(TEAM_ID, NOTE_ID, "Edited", null, null, null))
                .thenReturn(note("Edited", 2));

        mockMvc.perform(post("/api/teams/{teamId}/notes", TEAM_ID)
                        .header("Authorization", bearer(EDITOR_KEY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Created by editor\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/api/teams/{teamId}/notes/{noteId}", TEAM_ID, NOTE_ID)
                        .header("Authorization", bearer(EDITOR_KEY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Edited\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/teams/{teamId}/notes/{noteId}", TEAM_ID, NOTE_ID)
                        .header("Authorization", bearer(EDITOR_KEY)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/teams/{teamId}/members", TEAM_ID)
                        .header("Authorization", bearer(EDITOR_KEY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"new-user\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isForbidden());

        verify(notesService, never()).setMember(any(), any(), any());
    }

    @Test
    void validatesNoteInputAndVersion() throws Exception {
        authenticate(OWNER_KEY, owner);
        when(notesService.roleOf(TEAM_ID, owner.id())).thenReturn(Optional.of(Role.OWNER));

        mockMvc.perform(post("/api/teams/{teamId}/notes", TEAM_ID)
                        .header("Authorization", bearer(OWNER_KEY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("title is required"));

        mockMvc.perform(post("/api/teams/{teamId}/notes", TEAM_ID)
                        .header("Authorization", bearer(OWNER_KEY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Valid\",\"tags\":[\"good\",42]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("each tag must be a non-empty string up to 40 chars"));

        mockMvc.perform(put("/api/teams/{teamId}/notes/{noteId}", TEAM_ID, NOTE_ID)
                        .header("Authorization", bearer(OWNER_KEY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":\"old\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("version must be a number"));

        when(notesService.user("member-1"))
                .thenReturn(Optional.of(new User("member-1", "Member", "member-hash")));
        mockMvc.perform(post("/api/teams/{teamId}/members", TEAM_ID)
                        .header("Authorization", bearer(OWNER_KEY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"member-1\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("role must be OWNER, EDITOR or VIEWER"));

        verify(notesService, never()).createNote(any(), any(), any(), any(), any());
        verify(notesService, never()).updateNote(any(), any(), any(), any(), any(), any());
        verify(notesService, never()).setMember(any(), any(), any());
    }

    @Test
    void rejectsInvalidPageParameters() throws Exception {
        authenticate(OWNER_KEY, owner);

        mockMvc.perform(get("/api/teams/{teamId}/notes", TEAM_ID)
                        .header("Authorization", bearer(OWNER_KEY))
                        .param("limit", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid limit"));

        mockMvc.perform(get("/api/teams/{teamId}/notes", TEAM_ID)
                        .header("Authorization", bearer(OWNER_KEY))
                        .param("offset", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid offset"));

        verify(notesService, never()).search(any(), any(), any());
    }

    @Test
    void returnsRequestedPageAndTotalCount() throws Exception {
        authenticate(OWNER_KEY, owner);
        when(notesService.requireTeamAccess(TEAM_ID, owner.id())).thenReturn(new Team(TEAM_ID, "Platform"));
        when(notesService.search(TEAM_ID, "build", "work")).thenReturn(List.of(
                note("First", 1),
                note("Second", 1),
                note("Third", 1)));

        mockMvc.perform(get("/api/teams/{teamId}/notes", TEAM_ID)
                        .header("Authorization", bearer(OWNER_KEY))
                        .param("q", "build")
                        .param("tag", "work")
                        .param("limit", "1")
                        .param("offset", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.offset").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("Second"));

        verify(notesService).search(TEAM_ID, "build", "work");
    }

    @Test
    void returnsConflictWhenServiceRejectsStaleVersion() throws Exception {
        authenticate(OWNER_KEY, owner);
        when(notesService.roleOf(TEAM_ID, owner.id())).thenReturn(Optional.of(Role.OWNER));
        when(notesService.updateNote(TEAM_ID, NOTE_ID, null, "new body", null, 1L))
                .thenThrow(new ApiException(409, "Version conflict: current version is 2"));

        mockMvc.perform(put("/api/teams/{teamId}/notes/{noteId}", TEAM_ID, NOTE_ID)
                        .header("Authorization", bearer(OWNER_KEY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"new body\",\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Version conflict: current version is 2"));
    }

    private void authenticate(String apiKey, User user) {
        when(notesService.authenticate(apiKey)).thenReturn(Optional.of(user));
    }

    private String bearer(String apiKey) {
        return "Bearer " + apiKey;
    }

    private Note note(String title, long version) {
        return new Note(
                NOTE_ID,
                TEAM_ID,
                owner.id(),
                title,
                "Original body",
                List.of("work"),
                now,
                now,
                version);
    }
}
