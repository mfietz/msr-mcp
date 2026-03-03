package com.example.msrmcp.acceptance;

import com.example.msrmcp.db.*;
import com.example.msrmcp.helper.TestRepoBuilder;
import com.example.msrmcp.index.Indexer;
import com.example.msrmcp.model.IndexResult;
import com.example.msrmcp.tool.GetFileCommitHistoryTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance tests for the get_file_commit_history tool.
 *
 * <p>src/Main.java is changed in all 3 commits.
 * Expected: 3 commit records returned in reverse chronological order.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetFileCommitHistoryAcceptanceTest {

    Path repoDir;
    GetFileCommitHistoryTool historyTool;

    @BeforeAll
    void setUp() throws Exception {
        repoDir = new TestRepoBuilder()
                .commit("ABC-1 initial commit", "src/Main.java", "public class Main {}")
                .commit("ABC-2 add method",     "src/Main.java", "public class Main { void m(){} }")
                .commit("ABC-3 refactor",       "src/Main.java", "public class Main { void run(){} }")
                .build();

        Files.createDirectories(repoDir.resolve(".msr"));
        Database db = Database.open(repoDir.resolve(".msr/msr.db"));
        IndexResult result = Indexer.runFull(repoDir, db);
        assertThat(result.status()).isEqualTo("ok");

        CommitDao    commitDao     = db.attach(CommitDao.class);
        FileChangeDao fileChangeDao = db.attach(FileChangeDao.class);
        historyTool = new GetFileCommitHistoryTool(commitDao, fileChangeDao);
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void returns3Commits_forMainJava() {
        CallToolResult result = historyTool.handle(Map.of("filePath", "src/Main.java"));
        assertThat(result.isError()).isFalse();
        String json = ((TextContent) result.content().getFirst()).text();
        // All three commit messages appear
        assertThat(json).contains("ABC-1 initial commit");
        assertThat(json).contains("ABC-2 add method");
        assertThat(json).contains("ABC-3 refactor");
    }

    @Test
    void mostRecentCommit_appearsFirst() {
        CallToolResult result = historyTool.handle(Map.of("filePath", "src/Main.java"));
        String json = ((TextContent) result.content().getFirst()).text();
        // ABC-3 is newest → must appear before ABC-1
        assertThat(json.indexOf("ABC-3")).isLessThan(json.indexOf("ABC-1"));
    }

    @Test
    void jiraSlug_isExtracted() {
        CallToolResult result = historyTool.handle(Map.of("filePath", "src/Main.java"));
        String json = ((TextContent) result.content().getFirst()).text();
        assertThat(json).contains("\"jiraSlug\":\"ABC-1\"");
    }

    @Test
    void limitParam_constrainsResults() {
        CallToolResult result = historyTool.handle(Map.of("filePath", "src/Main.java", "limit", 1));
        String json = ((TextContent) result.content().getFirst()).text();
        // Only one commit entry — exactly one hash object
        long count = json.chars().filter(c -> c == '{').count();
        assertThat(count).isEqualTo(1);
    }
}
