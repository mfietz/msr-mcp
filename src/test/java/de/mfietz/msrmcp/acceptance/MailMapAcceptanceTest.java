package de.mfietz.msrmcp.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.mfietz.msrmcp.db.*;
import de.mfietz.msrmcp.helper.TestRepoBuilder;
import de.mfietz.msrmcp.index.IndexTracker;
import de.mfietz.msrmcp.index.Indexer;
import de.mfietz.msrmcp.tool.GetFileAuthorsTool;
import de.mfietz.msrmcp.tool.GetSummaryTool;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.*;

/**
 * Acceptance tests verifying that .mailmap author deduplication is applied during indexing.
 *
 * <p>Test repo has three commits from two "old" identities that map to the same canonical author.
 * After indexing with a .mailmap file present, all queries should reflect the canonical identity.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MailMapAcceptanceTest {

    Path repoDir;
    Database db;
    CommitDao commitDao;
    FileChangeDao fileChangeDao;
    FileMetricsDao fileMetricsDao;
    FileDao fileDao;

    @BeforeAll
    void setUp() throws Exception {
        repoDir =
                new TestRepoBuilder()
                        .commit(
                                "init",
                                "src/App.java",
                                "class App {}",
                                "Alice Smith",
                                "alice.smith@old.example.com")
                        .commit(
                                "feat: update",
                                "src/App.java",
                                "class App { void run() {} }",
                                "Alice",
                                "alice@old.example.com")
                        .commit(
                                "fix: typo",
                                "src/App.java",
                                "class App { void run() {} // fixed }",
                                "Alice Smith",
                                "alice.smith@old.example.com")
                        .build();

        // Write .mailmap: both old emails map to the canonical identity
        Files.writeString(
                repoDir.resolve(".mailmap"),
                """
                Alice Smith <alice@example.com> <alice.smith@old.example.com>
                Alice Smith <alice@example.com> <alice@old.example.com>
                """);

        Files.createDirectories(repoDir.resolve(".msr"));
        db = Database.open(repoDir.resolve(".msr/msr.db"));
        commitDao = db.attach(CommitDao.class);
        fileChangeDao = db.attach(FileChangeDao.class);
        fileMetricsDao = db.attach(FileMetricsDao.class);
        fileDao = db.attach(FileDao.class);

        Indexer.runFull(repoDir, db);
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void fileAuthors_returnsCanonicalEmailOnly() {
        String json =
                text(new GetFileAuthorsTool(commitDao).handle(Map.of("filePath", "src/App.java")));

        // Canonical email appears
        assertThat(json).contains("alice@example.com");
        // Old emails must not appear
        assertThat(json).doesNotContain("alice.smith@old.example.com");
        assertThat(json).doesNotContain("alice@old.example.com");
    }

    @Test
    void fileAuthors_returnsCanonicalNameOnly() {
        String json =
                text(new GetFileAuthorsTool(commitDao).handle(Map.of("filePath", "src/App.java")));

        assertThat(json).contains("Alice Smith");
        // The old short name must not appear as a distinct author
        assertThat(json).doesNotContain("\"authorName\":\"Alice\"");
    }

    @Test
    void fileAuthors_allCommitsConsolidatedUnderOneAuthor() {
        String json =
                text(new GetFileAuthorsTool(commitDao).handle(Map.of("filePath", "src/App.java")));

        // All 3 commits belong to a single canonical author
        assertThat(json).contains("\"commitCount\":3");
    }

    @Test
    void summary_uniqueAuthors_countsCanonicalIdentities() {
        IndexTracker tracker = new IndexTracker();
        tracker.markIndexing();
        tracker.markReady(0L);
        GetSummaryTool summary =
                new GetSummaryTool(commitDao, fileChangeDao, fileMetricsDao, fileDao, tracker);

        String json = text(summary.handle(Map.of()));

        // Only 1 unique author after deduplication — not 2
        assertThat(json).contains("\"uniqueAuthors\":1");
    }

    @Test
    void noMailmap_runsWithoutError() throws Exception {
        // Build a separate repo without a .mailmap file
        Path repo2 =
                new TestRepoBuilder()
                        .commit("init", "src/B.java", "class B {}", "Bob", "bob@example.com")
                        .build();
        Files.createDirectories(repo2.resolve(".msr"));
        Database db2 = Database.open(repo2.resolve(".msr/msr.db"));
        try {
            Indexer.runFull(repo2, db2);
            CommitDao dao2 = db2.attach(CommitDao.class);
            assertThat(dao2.count()).isEqualTo(1);
        } finally {
            TestRepoBuilder.deleteRecursively(repo2);
        }
    }

    private static String text(io.modelcontextprotocol.spec.McpSchema.CallToolResult r) {
        return ((TextContent) r.content().getFirst()).text();
    }
}
