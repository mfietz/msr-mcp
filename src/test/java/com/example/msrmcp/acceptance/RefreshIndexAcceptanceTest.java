package com.example.msrmcp.acceptance;

import com.example.msrmcp.db.*;
import com.example.msrmcp.helper.TestRepoBuilder;
import com.example.msrmcp.index.Indexer;
import com.example.msrmcp.model.IndexResult;
import com.example.msrmcp.tool.RefreshIndexTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance tests for the refresh_index tool.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefreshIndexAcceptanceTest {

    Path repoDir;
    Database db;
    CommitDao commitDao;
    RefreshIndexTool refreshTool;

    @BeforeAll
    void setUp() throws Exception {
        repoDir = new TestRepoBuilder()
                .commit("initial commit", "src/App.java", "public class App {}")
                .build();

        Files.createDirectories(repoDir.resolve(".msr"));
        db = Database.open(repoDir.resolve(".msr/msr.db"));
        commitDao = db.attach(CommitDao.class);

        // Initial index
        IndexResult initial = Indexer.runFull(repoDir, db);
        assertThat(initial.status()).isEqualTo("ok");
        assertThat(initial.commitsProcessed()).isEqualTo(1);

        refreshTool = new RefreshIndexTool(repoDir, db);
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void refreshReturnsOkStatus() {
        CallToolResult result = refreshTool.handle(Map.of());
        assertThat(result.isError()).isFalse();
        String json = ((TextContent) result.content().getFirst()).text();
        assertThat(json).contains("\"status\":\"ok\"");
    }

    @Test
    void refreshReportsNonZeroCounts() {
        CallToolResult result = refreshTool.handle(Map.of());
        String json = ((TextContent) result.content().getFirst()).text();
        assertThat(json).contains("\"commitsProcessed\":1");
    }

    @Test
    void doubleRefresh_isIdempotent() {
        refreshTool.handle(Map.of());
        CallToolResult result = refreshTool.handle(Map.of());
        String json = ((TextContent) result.content().getFirst()).text();
        assertThat(json).contains("\"status\":\"ok\"");
        // Commit count must still be 1 — no duplicates from double refresh
        assertThat(commitDao.count()).isEqualTo(1);
    }
}
