package notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class NotesControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private NotesService notesService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new NotesController(notesService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void healthEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
    }

    @Test
    void usersTeamsNotesAndPermissionsWork() throws Exception {
        User alice = new User("user-1", "Alice", "hash-a");
        User bob = new User("user-2", "Bob", "hash-b");

        when(notesService.createUser("Alice")).thenReturn(new NotesService.Issued(alice, "api-key-alice"));
        when(notesService.createUser("Bob")).thenReturn(new NotesService.Issued(bob, "api-key-bob"));
        when(notesService.authenticate("api-key-alice")).thenReturn(Optional.of(alice));
        when(notesService.authenticate("api-key-bob")).thenReturn(Optional.of(bob));

        Team team = new Team("team-1", "Platform");
        when(notesService.createTeam("Platform", "user-1")).thenReturn(team);
        when(notesService.roleOf("team-1", "user-1")).thenReturn(Optional.of(Role.OWNER));
        when(notesService.roleOf("team-1", "user-2")).thenReturn(Optional.of(Role.VIEWER));
        when(notesService.user("user-2")).thenReturn(Optional.of(bob));
        when(notesService.membersOf("team-1")).thenReturn(List.of(
                Map.of("userId", "user-1", "name", "Alice", "role", Role.OWNER),
                Map.of("userId", "user-2", "name", "Bob", "role", Role.VIEWER)));

        MvcResult aliceCreate = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alice\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode aliceBody = objectMapper.readTree(aliceCreate.getResponse().getContentAsString());
        assertThat(aliceBody.get("apiKey").asText()).isEqualTo("api-key-alice");

        MvcResult bobCreate = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bob\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode bobBody = objectMapper.readTree(bobCreate.getResponse().getContentAsString());
        assertThat(bobBody.get("apiKey").asText()).isEqualTo("api-key-bob");

        MvcResult teamCreate = mockMvc.perform(post("/api/teams")
                        .header("Authorization", "Bearer api-key-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Platform\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode teamBody = objectMapper.readTree(teamCreate.getResponse().getContentAsString());
        assertThat(teamBody.get("id").asText()).isEqualTo("team-1");

        mockMvc.perform(post("/api/teams/team-1/members")
                        .header("Authorization", "Bearer api-key-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"user-2\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk());

        Instant now = Instant.now();
        Note note = new Note(
                "note-1",
                "team-1",
                "user-1",
                "Standup",
                "ship v1",
                List.of("meeting", "weekly"),
                now,
                now,
                1L);
        when(notesService.createNote("team-1", "user-1", "Standup", "ship v1", List.of("meeting", "weekly")))
                .thenReturn(note);

        mockMvc.perform(post("/api/teams/team-1/notes")
                        .header("Authorization", "Bearer api-key-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Standup\",\"body\":\"ship v1\",\"tags\":[\"meeting\",\"weekly\"]}"))
                .andExpect(status().isCreated());

        when(notesService.search("team-1", "SHIP", "meeting")).thenReturn(List.of(note));
        mockMvc.perform(get("/api/teams/team-1/notes")
                        .header("Authorization", "Bearer api-key-alice")
                        .param("q", "SHIP")
                        .param("tag", "meeting"))
                .andExpect(status().isOk());

        Note updated = new Note(
                "note-1",
                "team-1",
                "user-1",
                "Standup",
                "ship v1 friday",
                List.of("meeting", "weekly"),
                now,
                Instant.now(),
                2L);
        when(notesService.updateNote(eq("team-1"), eq("note-1"), any(), any(), any(), eq(1L)))
                .thenReturn(updated);

        mockMvc.perform(put("/api/teams/team-1/notes/note-1")
                        .header("Authorization", "Bearer api-key-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"ship v1 friday\",\"version\":1}"))
                .andExpect(status().isOk());

        when(notesService.roleOf("team-1", "user-2")).thenReturn(Optional.of(Role.VIEWER));
        mockMvc.perform(post("/api/teams/team-1/notes")
                        .header("Authorization", "Bearer api-key-bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Nope\",\"body\":\"cant\"}"))
                .andExpect(status().isForbidden());

        when(notesService.note("team-1", "note-1")).thenReturn(Optional.of(note));
        mockMvc.perform(get("/api/teams/team-1/notes/note-1")
                        .header("Authorization", "Bearer api-key-alice"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/teams/team-1/notes/note-1")
                        .header("Authorization", "Bearer api-key-alice"))
                .andExpect(status().isNoContent());
    }
}
