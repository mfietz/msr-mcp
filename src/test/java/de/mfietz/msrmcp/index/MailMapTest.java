package de.mfietz.msrmcp.index;

import static org.assertj.core.api.Assertions.assertThat;

import de.mfietz.msrmcp.index.MailMap.Identity;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.*;

/** Unit tests for {@link MailMap} parsing and identity resolution. */
class MailMapTest {

    Path dir;

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createTempDirectory("mailmap-test-");
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.walk(dir)
                .sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(java.io.File::delete);
    }

    // ── Missing file ───────────────────────────────────────────────────────

    @Test
    void missingFile_returnsUnchangedIdentity() throws Exception {
        MailMap mm = MailMap.load(dir);

        Identity result = mm.resolve("Some Name", "some@example.com");

        assertThat(result.name()).isEqualTo("Some Name");
        assertThat(result.email()).isEqualTo("some@example.com");
    }

    // ── Comment and blank line handling ────────────────────────────────────

    @Test
    void commentsAndBlankLines_areIgnored() throws Exception {
        write("# This is a comment\n\n   \n# Another comment\n");
        MailMap mm = MailMap.load(dir);

        Identity result = mm.resolve("Any Name", "any@example.com");

        assertThat(result.name()).isEqualTo("Any Name");
        assertThat(result.email()).isEqualTo("any@example.com");
    }

    // ── "Proper Name <commit@email>" — name override ───────────────────────

    @Test
    void nameOverride_appliedWhenEmailMatches() throws Exception {
        write("Canonical Name <commit@example.com>\n");
        MailMap mm = MailMap.load(dir);

        Identity result = mm.resolve("Old Name", "commit@example.com");

        assertThat(result.name()).isEqualTo("Canonical Name");
        assertThat(result.email()).isEqualTo("commit@example.com");
    }

    @Test
    void nameOverride_notAppliedWhenEmailDiffers() throws Exception {
        write("Canonical Name <commit@example.com>\n");
        MailMap mm = MailMap.load(dir);

        Identity result = mm.resolve("Old Name", "other@example.com");

        assertThat(result.name()).isEqualTo("Old Name");
        assertThat(result.email()).isEqualTo("other@example.com");
    }

    // ── "<proper@email> <commit@email>" — email override ──────────────────

    @Test
    void emailOverride_appliedWhenCommitEmailMatches() throws Exception {
        write("<canonical@example.com> <old@example.com>\n");
        MailMap mm = MailMap.load(dir);

        Identity result = mm.resolve("Some Name", "old@example.com");

        assertThat(result.name()).isEqualTo("Some Name");
        assertThat(result.email()).isEqualTo("canonical@example.com");
    }

    // ── "Proper Name <proper@email> <commit@email>" — both overrides ───────

    @Test
    void bothOverride_appliedWhenCommitEmailMatches() throws Exception {
        write("Canonical Name <canonical@example.com> <old@example.com>\n");
        MailMap mm = MailMap.load(dir);

        Identity result = mm.resolve("Old Name", "old@example.com");

        assertThat(result.name()).isEqualTo("Canonical Name");
        assertThat(result.email()).isEqualTo("canonical@example.com");
    }

    // ── "Proper Name <proper@email> Commit Name <commit@email>" — name+email match ──

    @Test
    void nameAndEmailMatch_overridesBoth() throws Exception {
        write("Canonical Name <canonical@example.com> Commit Name <old@example.com>\n");
        MailMap mm = MailMap.load(dir);

        Identity result = mm.resolve("Commit Name", "old@example.com");

        assertThat(result.name()).isEqualTo("Canonical Name");
        assertThat(result.email()).isEqualTo("canonical@example.com");
    }

    @Test
    void nameAndEmailMatch_notAppliedWhenNameDiffers() throws Exception {
        write("Canonical Name <canonical@example.com> Commit Name <old@example.com>\n");
        MailMap mm = MailMap.load(dir);

        // Name does not match — fall through (no mapping)
        Identity result = mm.resolve("Different Name", "old@example.com");

        assertThat(result.name()).isEqualTo("Different Name");
        assertThat(result.email()).isEqualTo("old@example.com");
    }

    // ── Email matching is case-insensitive ─────────────────────────────────

    @Test
    void emailMatch_isCaseInsensitive() throws Exception {
        write("Canonical Name <commit@example.com>\n");
        MailMap mm = MailMap.load(dir);

        Identity result = mm.resolve("Old Name", "COMMIT@EXAMPLE.COM");

        assertThat(result.name()).isEqualTo("Canonical Name");
    }

    // ── Multiple entries — first match wins ────────────────────────────────

    @Test
    void multipleEntries_firstMatchWins() throws Exception {
        write(
                """
                First Match <first@example.com> <old@example.com>
                Second Match <second@example.com> <old@example.com>
                """);
        MailMap mm = MailMap.load(dir);

        Identity result = mm.resolve("Any", "old@example.com");

        assertThat(result.email()).isEqualTo("first@example.com");
    }

    // ── Helper ─────────────────────────────────────────────────────────────

    private void write(String content) throws Exception {
        Files.writeString(dir.resolve(".mailmap"), content);
    }
}
