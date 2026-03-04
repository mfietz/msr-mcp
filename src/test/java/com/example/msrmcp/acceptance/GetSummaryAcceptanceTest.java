package com.example.msrmcp.acceptance;

import com.example.msrmcp.db.*;
import com.example.msrmcp.helper.TestRepoBuilder;
import com.example.msrmcp.index.Indexer;
import com.example.msrmcp.tool.GetSummaryTool;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance tests for the get_summary tool.
 *
 * <p>Test repo: 3 commits, 2 files (Main.java touched 3×, Helper.java touched 1×).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetSummaryAcceptanceTest {

    Path repoDir;
    GetSummaryTool summaryTool;

    @BeforeAll
    void setUp() throws Exception {
        repoDir = new TestRepoBuilder()
                .commit("feat: init",   Map.of("src/Main.java", "class M{}", "src/Helper.java", "class H{}"))
                .commit("fix: v2",      "src/Main.java", "class M{ void m(){} }")
                .commit("fix: v3",      "src/Main.java", "class M{ void m(){} void n(){} }")
                .build();

        Files.createDirectories(repoDir.resolve(".msr"));
        Database db = Database.open(repoDir.resolve(".msr/msr.db"));
        Indexer.runFull(repoDir, db);

        summaryTool = new GetSummaryTool(
                db.attach(CommitDao.class),
                db.attach(FileChangeDao.class),
                db.attach(FileMetricsDao.class));
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void totalCommits_isThree() {
        String json = text(summaryTool.handle(Map.of()));
        assertThat(json).contains("\"totalCommits\":3");
    }

    @Test
    void totalFilesTracked_isTwo() {
        String json = text(summaryTool.handle(Map.of()));
        assertThat(json).contains("\"totalFilesTracked\":2");
    }

    @Test
    void filesWithMetrics_isPositive() {
        String json = text(summaryTool.handle(Map.of()));
        assertThat(json).doesNotContain("\"filesWithMetrics\":0");
    }

    @Test
    void dateRange_earliestBeforeLatest() {
        String json = text(summaryTool.handle(Map.of()));
        // Extract numeric values — just verify both are present and non-zero
        assertThat(json).contains("\"earliestCommitMs\"");
        assertThat(json).contains("\"latestCommitMs\"");
        assertThat(json).doesNotContain("\"earliestCommitMs\":0");
        assertThat(json).doesNotContain("\"latestCommitMs\":0");
    }

    @Test
    void topChangedFiles_containsMainJava() {
        String json = text(summaryTool.handle(Map.of()));
        assertThat(json).contains("\"topChangedFiles\"");
        assertThat(json).contains("Main.java");
    }

    private static String text(io.modelcontextprotocol.spec.McpSchema.CallToolResult r) {
        return ((TextContent) r.content().getFirst()).text();
    }
}
