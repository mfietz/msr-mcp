package com.example.msrmcp.acceptance;

import com.example.msrmcp.db.*;
import com.example.msrmcp.helper.TestRepoBuilder;
import com.example.msrmcp.index.Indexer;
import com.example.msrmcp.tool.GetFileCouplingTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance tests for the get_file_coupling tool.
 *
 * <p>Test repo: A and B always co-change (3 commits). C appears with both in one commit.
 * Expected: querying A returns B as top partner; querying unknown file returns [].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetFileCouplingAcceptanceTest {

    Path repoDir;
    GetFileCouplingTool tool;

    @BeforeAll
    void setUp() throws Exception {
        repoDir = new TestRepoBuilder()
                .commit("init", Map.of(
                        "src/A.java", "class A {}",
                        "src/B.java", "class B {}"))
                .commit("feat: a+b", Map.of(
                        "src/A.java", "class A { void m(){} }",
                        "src/B.java", "class B { void m(){} }"))
                .commit("feat: a+b+c", Map.of(
                        "src/A.java", "class A { void m(){} void n(){} }",
                        "src/B.java", "class B { void m(){} void n(){} }",
                        "src/C.java", "class C {}"))
                .build();

        Files.createDirectories(repoDir.resolve(".msr"));
        Database db = Database.open(repoDir.resolve(".msr/msr.db"));
        Indexer.runFull(repoDir, db);
        tool = new GetFileCouplingTool(db.attach(FileCouplingDao.class));
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void queryA_returnsBAsTopPartner() {
        CallToolResult result = tool.handle(Map.of("filePath", "src/A.java", "topN", 5));
        assertThat(result.isError()).isFalse();
        String json = text(result);
        assertThat(json).contains("src/B.java");
        // B must appear before C (higher coupling ratio)
        int bIdx = json.indexOf("src/B.java");
        int cIdx = json.indexOf("src/C.java");
        if (cIdx != -1) {
            assertThat(bIdx).isLessThan(cIdx);
        }
    }

    @Test
    void queryB_returnsAAsTopPartner() {
        // Symmetric: B is stored as file_b in some rows; query still resolves correctly
        String json = text(tool.handle(Map.of("filePath", "src/B.java", "topN", 5)));
        assertThat(json).contains("src/A.java");
    }

    @Test
    void queryA_partnerRow_hasExpectedFields() {
        String json = text(tool.handle(Map.of("filePath", "src/A.java", "topN", 1, "minCoupling", 0.9)));
        assertThat(json).contains("\"partnerPath\"");
        assertThat(json).contains("\"coChanges\":3");
        assertThat(json).contains("\"couplingRatio\":1.0");
        assertThat(json).contains("\"targetTotalChanges\":3");
        assertThat(json).contains("\"partnerTotalChanges\":3");
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
        // Commits are 2024-01-01T00–02:00Z; a 2025 cutoff excludes all
        long cutoff = java.time.Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();
        String json = text(tool.handle(Map.of("filePath", "src/A.java", "sinceEpochMs", cutoff)));
        assertThat(json).isEqualTo("[]");
    }

    @Test
    void sinceEpochMs_afterFirstCommit_onlyCountsRecentCoChanges() {
        // commit1 (T+0h): A+B init
        // commit2 (T+1h): A+B changed
        // commit3 (T+2h): A+B+C changed
        // Cutoff between commit1 and commit2 → only commits 2+3 count (B still top partner)
        long afterCommit1 = java.time.Instant.parse("2024-01-01T00:30:00Z").toEpochMilli();
        String json = text(tool.handle(Map.of("filePath", "src/A.java", "sinceEpochMs", afterCommit1)));
        assertThat(json).contains("src/B.java");
        assertThat(json).contains("\"coChanges\":2");
    }

    private static String text(CallToolResult r) {
        return ((TextContent) r.content().getFirst()).text();
    }
}
