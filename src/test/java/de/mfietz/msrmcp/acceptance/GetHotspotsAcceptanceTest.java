package de.mfietz.msrmcp.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.mfietz.msrmcp.db.*;
import de.mfietz.msrmcp.helper.TestRepoBuilder;
import de.mfietz.msrmcp.index.Indexer;
import de.mfietz.msrmcp.model.IndexResult;
import de.mfietz.msrmcp.tool.GetHotspotsTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.*;

/**
 * Acceptance tests for the get_hotspots tool.
 *
 * <p>Test repo layout:
 *
 * <ul>
 *   <li>src/Main.java — changed in 3 commits (highest churn)
 *   <li>src/Helper.java — changed in 1 commit
 * </ul>
 *
 * Expected: Main.java appears first with changeFrequency=3.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetHotspotsAcceptanceTest {

    static final String MAIN_JAVA =
            """
            public class Main {
                public int compute(int x) {
                    if (x > 0) {
                        return x * 2;
                    } else {
                        return -x;
                    }
                }
            }
            """;

    static final String HELPER_JAVA =
            """
            public class Helper {
                public String greet(String name) {
                    return "Hello, " + name;
                }
            }
            """;

    Path repoDir;
    Path dbPath;
    Database db;
    CommitDao commitDao;
    FileChangeDao fileChangeDao;
    FileMetricsDao fileMetricsDao;
    FileCouplingDao fileCouplingDao;
    GetHotspotsTool hotspotsTool;

    @BeforeAll
    void setUp() throws Exception {
        repoDir =
                new TestRepoBuilder()
                        .commit(
                                "feat: initial",
                                Map.of("src/Main.java", MAIN_JAVA, "src/Helper.java", HELPER_JAVA))
                        .commit(
                                "fix: iteration",
                                "src/Main.java",
                                MAIN_JAVA.replace("x * 2", "x + x"))
                        .commit(
                                "fix: edge case",
                                "src/Main.java",
                                MAIN_JAVA.replace("x > 0", "x >= 0"))
                        .build();

        Files.createDirectories(repoDir.resolve(".msr"));
        dbPath = repoDir.resolve(".msr/msr.db");
        db = Database.open(dbPath);
        commitDao = db.attach(CommitDao.class);
        fileChangeDao = db.attach(FileChangeDao.class);
        fileMetricsDao = db.attach(FileMetricsDao.class);
        fileCouplingDao = db.attach(FileCouplingDao.class);

        IndexResult result = Indexer.runFull(repoDir, db);
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.commitsProcessed()).isEqualTo(3);

        hotspotsTool = new GetHotspotsTool(fileChangeDao, fileMetricsDao);
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void topFile_isMainJava_withFrequency3() {
        CallToolResult result = hotspotsTool.handle(Map.of("topN", 5));
        assertThat(result.isError()).isFalse();

        String json = ((TextContent) result.content().getFirst()).text();
        assertThat(json).contains("Main.java");

        // Main.java should appear first (highest churn)
        int mainIdx = json.indexOf("Main.java");
        int helperIdx = json.indexOf("Helper.java");
        assertThat(mainIdx).isLessThan(helperIdx);
    }

    @Test
    void changeFrequency_forMainJava_is3() {
        CallToolResult result = hotspotsTool.handle(Map.of("topN", 1));
        String json = ((TextContent) result.content().getFirst()).text();
        // 3 commits touched Main.java
        assertThat(json).contains("\"changeFrequency\":3");
    }

    @Test
    void extensionFilter_limitsResults() {
        // Filter for .java files — should still return results
        CallToolResult result = hotspotsTool.handle(Map.of("topN", 5, "extension", ".java"));
        String json = ((TextContent) result.content().getFirst()).text();
        assertThat(json).contains("Main.java");
    }

    @Test
    void pmdMetrics_arePopulated() {
        // PMD must have run and populated file_metrics
        int count = fileMetricsDao.count();
        System.err.println("file_metrics count: " + count);
        assertThat(count).as("PMD must have analyzed at least one file").isGreaterThan(0);

        // Main.java has one method with an if/else → cyclo >= 2, loc > 0
        CallToolResult result = hotspotsTool.handle(Map.of("topN", 1));
        String json = ((TextContent) result.content().getFirst()).text();
        System.err.println("hotspot JSON: " + json);
        assertThat(json).doesNotContain("\"linesOfCode\":0");
        assertThat(json).doesNotContain("\"cyclomaticComplexity\":-1");
    }

    @Test
    void sinceEpochMs_excludesCommitsBeforeCutoff() {
        // All test commits are from 2024-01-01; a 2025 cutoff should exclude them all
        long cutoff = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();
        CallToolResult result = hotspotsTool.handle(Map.of("topN", 5, "sinceEpochMs", cutoff));
        String json = ((TextContent) result.content().getFirst()).text();
        assertThat(json).isEqualTo("[]");
    }

    @Test
    void topN_limitsResultCount() {
        // Repo has 2 files; topN=1 must return exactly 1 JSON object
        CallToolResult result = hotspotsTool.handle(Map.of("topN", 1));
        String json = ((TextContent) result.content().getFirst()).text();
        long objectCount = json.chars().filter(c -> c == '{').count();
        assertThat(objectCount).isEqualTo(1);
    }

    @Test
    void hotspotResult_containsAgeFields() {
        CallToolResult result = hotspotsTool.handle(Map.of("topN", 1));
        String json = ((TextContent) result.content().getFirst()).text();

        // Both fields must be present
        assertThat(json).contains("\"ageInDays\"");
        assertThat(json).contains("\"daysSinceLastChange\"");

        // Internal epoch-ms fields must NOT leak into JSON output
        assertThat(json).doesNotContain("firstCommitMs");
        assertThat(json).doesNotContain("lastCommitMs");

        // Test commits are from 2024; running in 2026 → at least 300 days old
        assertThat(json).doesNotContain("\"ageInDays\":0");
        assertThat(json).doesNotContain("\"daysSinceLastChange\":0");
    }
}
