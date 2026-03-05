package de.mfietz.msrmcp.acceptance;

import de.mfietz.msrmcp.db.*;
import de.mfietz.msrmcp.helper.TestRepoBuilder;
import de.mfietz.msrmcp.index.Indexer;
import de.mfietz.msrmcp.model.IndexResult;
import de.mfietz.msrmcp.tool.GetTemporalCouplingTool;
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
        // A-B co-changed in all 3 commits → coChanges=3, ratio=1.0
        assertThat(json).contains("\"coChanges\":3");
        assertThat(json).contains("\"couplingRatio\":1.0");

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
        // Use epoch 0 so all test commits are included
        CallToolResult result = couplingTool.handle(Map.of("topN", 5, "minCoupling", 0.1,
                "sinceEpochMs", 0));
        assertThat(result.isError()).isFalse();
        String json = ((TextContent) result.content().getFirst()).text();
        assertThat(json).contains("A.java");
        assertThat(json).contains("B.java");
    }

}
