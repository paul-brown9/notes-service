package notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

@ExtendWith(MockitoExtension.class)
class NotesServiceTest {
    private static final String TEAM_ID = "team-1";
    private static final String NOTE_ID = "note-1";

    private NotesService notesService;

    @Mock
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        notesService = new NotesService(mongoTemplate);
    }

    @Test
    void updateKeepsUnchangedFieldsAndIncrementsVersion() {
        Instant createdAt = Instant.parse("2025-01-01T00:00:00Z");
        Note current = new Note(
                NOTE_ID,
                TEAM_ID,
                "author-1",
                "Old title",
                "Old body",
                List.of("old-tag"),
                createdAt,
                createdAt,
                4);
        when(mongoTemplate.findById(NOTE_ID, Note.class, "notes")).thenReturn(current);
        when(mongoTemplate.save(org.mockito.ArgumentMatchers.any(Note.class), org.mockito.ArgumentMatchers.eq("notes")))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Note updated = notesService.updateNote(TEAM_ID, NOTE_ID, null, "New body", null, 4L);

        assertThat(updated.id()).isEqualTo(NOTE_ID);
        assertThat(updated.title()).isEqualTo("Old title");
        assertThat(updated.body()).isEqualTo("New body");
        assertThat(updated.tags()).containsExactly("old-tag");
        assertThat(updated.createdAt()).isEqualTo(createdAt);
        assertThat(updated.updatedAt()).isAfterOrEqualTo(createdAt);
        assertThat(updated.version()).isEqualTo(5);

        ArgumentCaptor<Note> savedNote = ArgumentCaptor.forClass(Note.class);
        verify(mongoTemplate).save(savedNote.capture(), org.mockito.ArgumentMatchers.eq("notes"));
        assertThat(savedNote.getValue()).isEqualTo(updated);
    }

    @Test
    void updateRejectsStaleVersionWithoutSaving() {
        Note current = new Note(
                NOTE_ID,
                TEAM_ID,
                "author-1",
                "Title",
                "Body",
                List.of(),
                Instant.now(),
                Instant.now(),
                2);
        when(mongoTemplate.findById(NOTE_ID, Note.class, "notes")).thenReturn(current);

        assertThatThrownBy(() -> notesService.updateNote(TEAM_ID, NOTE_ID, null, "Stale", null, 1L))
                .isInstanceOf(ApiException.class)
                .hasMessage("Version conflict: current version is 2");

        verify(mongoTemplate, never()).save(org.mockito.ArgumentMatchers.any(Note.class), org.mockito.ArgumentMatchers.eq("notes"));
    }

    @Test
    void noteLookupDoesNotReturnAnotherTeamsNote() {
        Note note = new Note(
                NOTE_ID,
                TEAM_ID,
                "author-1",
                "Title",
                "Body",
                List.of(),
                Instant.now(),
                Instant.now(),
                1);
        when(mongoTemplate.findById(NOTE_ID, Note.class, "notes")).thenReturn(note);

        assertThat(notesService.note("another-team", NOTE_ID)).isEmpty();
    }
}
