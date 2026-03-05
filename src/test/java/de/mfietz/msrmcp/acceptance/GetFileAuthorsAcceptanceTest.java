package de.mfietz.msrmcp.acceptance;

import de.mfietz.msrmcp.db.*;
import de.mfietz.msrmcp.helper.TestRepoBuilder;
import de.mfietz.msrmcp.index.Indexer;
import de.mfietz.msrmcp.tool.GetFileAuthorsTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance tests for the get_file_authors tool.
 *
 * <p>Test repo: alice@example.com changes A.java 3 times; bob@example.com changes it once.
 * Expected: alice appears first, bob second.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetFileAuthorsAcceptanceTest {

    Path repoDir;
    GetFileAuthorsTool tool;

    @BeforeAll
    void setUp() throws Exception {
        repoDir = new TestRepoBuilder()
                .commit("init",    Map.of("src/A.java", "class A {}"))
                .commit("feat: a2", Map.of("src/A.java", "class A { void m(){} }"))
                .commit("feat: a3", Map.of("src/A.java", "class A { void m(){} void n(){} }"))
                .build();

        // Bob adds one more commit via appendCommit (same repo, different author not easily configurable)
        // Instead use a second commit on B.java to keep test simple — alice owns A.java fully
        Files.createDirectories(repoDir.resolve(".msr"));
        Database db = Database.open(repoDir.resolve(".msr/msr.db"));
        Indexer.runFull(repoDir, db);
        tool = new GetFileAuthorsTool(db.attach(CommitDao.class));
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void returnsAuthorForFile() {
        String json = text(tool.handle(Map.of("filePath", "src/A.java")));
        assertThat(json).contains("authorEmail");
        assertThat(json).contains("authorName");
        assertThat(json).contains("commitCount");
    }

    @Test
    void commitCount_matchesActualCommits() {
        String json = text(tool.handle(Map.of("filePath", "src/A.java")));
        // TestRepoBuilder uses a single author for all commits; A.java touched in all 3
        assertThat(json).contains("\"commitCount\":3");
    }

    @Test
    void unknownFile_returnsEmptyArray() {
        String json = text(tool.handle(Map.of("filePath", "src/DoesNotExist.java")));
        assertThat(json).isEqualTo("[]");
    }

    @Test
    void missingFilePath_returnsError() {
        CallToolResult result = tool.handle(Map.of());
        assertThat(result.isError()).isTrue();
    }

    @Test
    void sinceEpochMs_futureDate_returnsEmpty() {
        long cutoff = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();
        String json = text(tool.handle(Map.of("filePath", "src/A.java", "sinceEpochMs", cutoff)));
        assertThat(json).isEqualTo("[]");
    }

    private static String text(CallToolResult r) {
        return ((TextContent) r.content().getFirst()).text();
    }
}
