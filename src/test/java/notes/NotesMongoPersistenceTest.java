package notes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class NotesMongoPersistenceTest {
    @Container
    private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @Autowired
    private NotesService notesService;

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
    }

    @Test
    void createsReadsUpdatesAndDeletesNoteInMongo() {
        Note created = notesService.createNote(
                "team-integration",
                "author-integration",
                "Mongo persistence check",
                "Stored in the notes collection",
                List.of("integration"));

        assertThat(created.id()).isNotBlank();
        Note stored = notesService.note("team-integration", created.id()).orElseThrow();
        assertThat(stored.title()).isEqualTo("Mongo persistence check");
        assertThat(stored.body()).isEqualTo("Stored in the notes collection");
        assertThat(stored.tags()).containsExactly("integration");
        assertThat(stored.version()).isEqualTo(1);

        Note updated = notesService.updateNote(
                "team-integration",
                created.id(),
                null,
                "Updated in MongoDB",
                null,
                1L);
        assertThat(updated.body()).isEqualTo("Updated in MongoDB");
        assertThat(updated.version()).isEqualTo(2);

        notesService.deleteNote("team-integration", created.id());
        assertThat(notesService.note("team-integration", created.id())).isEmpty();
    }
}
