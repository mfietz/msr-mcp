package com.example.msrmcp.acceptance;

import com.example.msrmcp.db.*;
import com.example.msrmcp.helper.TestRepoBuilder;
import com.example.msrmcp.index.Indexer;
import com.example.msrmcp.tool.GetChurnTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.*;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance tests for the get_churn tool.
 *
 * <p>Test repo layout:
 * <ul>
 *   <li>src/Main.java — 3 commits, each adding many lines
 *   <li>src/Helper.java — 1 commit, minimal content
 * </ul>
 * Expected: Main.java has higher churn than Helper.java.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetChurnAcceptanceTest {

    private Path repoDir;
    private GetChurnTool churnTool;

    @BeforeAll
    void setUp() throws Exception {
        repoDir = new TestRepoBuilder()
                .commit("feat: init", Map.of(
                        "src/Main.java", "public class Main {\n    void a(){}\n    void b(){}\n    void c(){}\n}\n",
                        "src/Helper.java", "public class Helper {}\n"))
                .commit("feat: add logic", "src/Main.java",
                        "public class Main {\n    void a(){}\n    void b(){}\n    void c(){}\n    void d(){}\n    void e(){}\n}\n")
                .commit("feat: more logic", "src/Main.java",
                        "public class Main {\n    void a(){}\n    void b(){}\n    void c(){}\n    void d(){}\n    void e(){}\n    void f(){}\n}\n")
                .build();

        Path dbPath = repoDir.resolve(".msr/msr.db");
        java.nio.file.Files.createDirectories(dbPath.getParent());
        Database db = Database.open(dbPath);
        Indexer.runFull(repoDir, db);

        FileChangeDao fileChangeDao = db.attach(FileChangeDao.class);
        churnTool = new GetChurnTool(fileChangeDao);
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void topFile_hasHighestChurn() {
        CallToolResult result = churnTool.handle(Map.of("topN", 5));
        String json = ((TextContent) result.content().getFirst()).text();

        assertThat(result.isError()).isFalse();
        assertThat(json).startsWith("[");
        // Main.java was changed 3 times with lines added each time → highest churn
        assertThat(json.indexOf("Main.java")).isLessThan(json.indexOf("Helper.java"));
    }

    @Test
    void churnRowContainsExpectedFields() {
        CallToolResult result = churnTool.handle(Map.of("topN", 1));
        String json = ((TextContent) result.content().getFirst()).text();

        assertThat(json).contains("filePath");
        assertThat(json).contains("linesAdded");
        assertThat(json).contains("linesDeleted");
        assertThat(json).contains("churn");
        assertThat(json).contains("changeFrequency");
    }

    @Test
    void topN_limitsResults() {
        CallToolResult result = churnTool.handle(Map.of("topN", 1));
        String json = ((TextContent) result.content().getFirst()).text();

        long objectCount = json.chars().filter(c -> c == '{').count();
        assertThat(objectCount).isEqualTo(1);
    }

    @Test
    void sinceEpochMs_futureDate_returnsEmpty() {
        long future = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();
        CallToolResult result = churnTool.handle(Map.of("sinceEpochMs", future));
        String json = ((TextContent) result.content().getFirst()).text();

        assertThat(json).isEqualTo("[]");
    }

    @Test
    void extensionFilter_returnsOnlyMatchingFiles() {
        CallToolResult result = churnTool.handle(Map.of("extension", ".java", "topN", 10));
        String json = ((TextContent) result.content().getFirst()).text();

        assertThat(result.isError()).isFalse();
        assertThat(json).doesNotContain(".ts");
    }
}
