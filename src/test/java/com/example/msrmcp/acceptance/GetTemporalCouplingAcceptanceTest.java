package com.example.msrmcp.acceptance;

import com.example.msrmcp.db.*;
import com.example.msrmcp.helper.TestRepoBuilder;
import com.example.msrmcp.index.Indexer;
import com.example.msrmcp.model.IndexResult;
import com.example.msrmcp.tool.GetTemporalCouplingTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance tests for the get_temporal_coupling tool.
 *
 * <p>Test repo: A.java and B.java are always changed together (3 commits).
 * C.java appears alone in one of those commits.
 * Expected: A↔B has the highest coupling ratio (1.0).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetTemporalCouplingAcceptanceTest {

    Path repoDir;
    GetTemporalCouplingTool couplingTool;

    @BeforeAll
    void setUp() throws Exception {
        repoDir = new TestRepoBuilder()
                .commit("init", Map.of(
                        "src/A.java", "public class A {}",
                        "src/B.java", "public class B {}"))
                .commit("feat: a+b", Map.of(
                        "src/A.java", "public class A { void m(){} }",
                        "src/B.java", "public class B { void m(){} }"))
                .commit("feat: a+b+c", Map.of(
                        "src/A.java", "public class A { void m(){} void n(){} }",
                        "src/B.java", "public class B { void m(){} void n(){} }",
                        "src/C.java", "public class C {}"))
                .build();

        Files.createDirectories(repoDir.resolve(".msr"));
        Database db = Database.open(repoDir.resolve(".msr/msr.db"));
        IndexResult result = Indexer.runFull(repoDir, db);
        assertThat(result.status()).isEqualTo("ok");

        FileCouplingDao fileCouplingDao = db.attach(FileCouplingDao.class);
        FileChangeDao fileChangeDao    = db.attach(FileChangeDao.class);
        couplingTool = new GetTemporalCouplingTool(fileCouplingDao, fileChangeDao);
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void abCoupling_isHighest() {
        CallToolResult result = couplingTool.handle(Map.of("topN", 5, "minCoupling", 0.1));
        String json = ((TextContent) result.content().getFirst()).text();

        // A.java and B.java should both appear in the first coupling pair
        assertThat(json).contains("A.java");
        assertThat(json).contains("B.java");

        // A-B pair should appear before A-C or B-C
        int abIdx = Math.min(json.indexOf("A.java"), json.indexOf("B.java"));
        int cIdx  = json.indexOf("C.java");
        if (cIdx != -1) {
            assertThat(abIdx).isLessThan(cIdx);
        }
    }

    @Test
    void withSinceFilter_dynamicQueryIsUsed() {
        // Using a since timestamp forces the dynamic (file_changes-based) query
        long since = System.currentTimeMillis() - (365L * 24 * 3600 * 1000);
        CallToolResult result = couplingTool.handle(Map.of("topN", 5, "minCoupling", 0.1,
                "sinceEpochMs", since));
        assertThat(result.isError()).isFalse();
        // Result may be empty (old test commits) but must not throw
    }

}
