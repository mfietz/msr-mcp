package de.mfietz.msrmcp.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.mfietz.msrmcp.db.*;
import de.mfietz.msrmcp.helper.TestRepoBuilder;
import de.mfietz.msrmcp.index.Indexer;
import de.mfietz.msrmcp.tool.GetBusFactorTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.*;

/**
 * Acceptance tests for the get_bus_factor tool.
 *
 * <p>Test repo: A.java changed in all 3 commits (single author → dominance 1.0). B.java changed
 * only once (still single author, dominance 1.0). With threshold=1.0 both appear; the
 * dominance_ratio and field names are verified.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetBusFactorAcceptanceTest {

    Path repoDir;
    GetBusFactorTool tool;

    @BeforeAll
    void setUp() throws Exception {
        repoDir =
                new TestRepoBuilder()
                        .commit(
                                "init",
                                Map.of(
                                        "src/A.java", "class A {}",
                                        "src/B.java", "class B {}"))
                        .commit("feat: a2", Map.of("src/A.java", "class A { void m(){} }"))
                        .commit(
                                "feat: a3",
                                Map.of("src/A.java", "class A { void m(){} void n(){} }"))
                        .build();

        Files.createDirectories(repoDir.resolve(".msr"));
        Database db = Database.open(repoDir.resolve(".msr/msr.db"));
        Indexer.runFull(repoDir, db);
        tool = new GetBusFactorTool(db.attach(CommitDao.class));
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void returnsFilesAboveThreshold() {
        // All files have dominance 1.0 (single author); threshold 0.5 should return both
        String json = text(tool.handle(Map.of("threshold", 0.5)));
        assertThat(json).contains("src/A.java");
        assertThat(json).contains("src/B.java");
    }

    @Test
    void resultHasExpectedFields() {
        String json = text(tool.handle(Map.of("threshold", 0.5, "topN", 1)));
        assertThat(json).contains("\"filePath\"");
        assertThat(json).contains("\"topAuthorEmail\"");
        assertThat(json).contains("\"topAuthorName\"");
        assertThat(json).contains("\"topAuthorCommits\"");
        assertThat(json).contains("\"totalCommits\"");
        assertThat(json).contains("\"dominanceRatio\":1.0");
    }

    @Test
    void aJava_hasHighestTotalCommits() {
        String json = text(tool.handle(Map.of("threshold", 0.5)));
        // A.java appears in 3 commits, B.java in 1 → A.java first
        assertThat(json.indexOf("src/A.java")).isLessThan(json.indexOf("src/B.java"));
    }

    @Test
    void highThreshold_belowDominance_returnsEmpty() {
        // dominance is always 1.0; threshold > 1 returns nothing
        String json = text(tool.handle(Map.of("threshold", 1.1)));
        assertThat(json).isEqualTo("[]");
    }

    @Test
    void sinceEpochMs_futureDate_returnsEmpty() {
        long cutoff = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();
        String json = text(tool.handle(Map.of("threshold", 0.5, "sinceEpochMs", cutoff)));
        assertThat(json).isEqualTo("[]");
    }

    private static String text(CallToolResult r) {
        return ((TextContent) r.content().getFirst()).text();
    }
}
