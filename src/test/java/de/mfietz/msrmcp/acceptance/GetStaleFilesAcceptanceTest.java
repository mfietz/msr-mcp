package de.mfietz.msrmcp.acceptance;

import de.mfietz.msrmcp.db.*;
import de.mfietz.msrmcp.helper.TestRepoBuilder;
import de.mfietz.msrmcp.index.Indexer;
import de.mfietz.msrmcp.model.IndexResult;
import de.mfietz.msrmcp.tool.GetStaleFilesTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance tests for the get_stale_files tool.
 *
 * <p>Test repo layout:
 * <ul>
 *   <li>src/Complex.java — committed once, 2024-01-01 (~790 days ago); has branches (cyclo > 1)
 *   <li>src/Simple.java  — committed once, 2024-01-01 (~790 days ago); linear (cyclo = 1)
 * </ul>
 *
 * <p>Both files are ~790 days old. minDaysStale=365 includes them; minDaysStale=1000 excludes them.
 * Complex.java should rank above Simple.java (higher complexity → higher staleness score).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetStaleFilesAcceptanceTest {

    // Two if-branches → cyclomatic complexity = 3 (PMD counts: method base + 2 ifs)
    static final String COMPLEX_JAVA = """
            public class Complex {
                public int work(int x) {
                    if (x > 10) {
                        if (x > 20) { return 3; }
                        return 2;
                    }
                    return 1;
                }
            }
            """;

    // Linear method → cyclomatic complexity = 1
    static final String SIMPLE_JAVA = """
            public class Simple {
                public String greet(String name) {
                    return "Hello, " + name;
                }
            }
            """;

    Path repoDir;
    Database db;
    GetStaleFilesTool tool;

    @BeforeAll
    void setUp() throws Exception {
        repoDir = new TestRepoBuilder()
                .commit("feat: initial",
                        Map.of("src/Complex.java", COMPLEX_JAVA,
                               "src/Simple.java",  SIMPLE_JAVA))
                .build();

        Files.createDirectories(repoDir.resolve(".msr"));
        db = Database.open(repoDir.resolve(".msr/msr.db"));
        IndexResult result = Indexer.runFull(repoDir, db);
        assertThat(result.status()).isEqualTo("ok");

        tool = new GetStaleFilesTool(db.attach(FileChangeDao.class), db.attach(FileMetricsDao.class));
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void staleFilesAppear_whenMinDaysIsLow() {
        // Commits are ~790 days old; 365-day threshold should include them
        CallToolResult result = tool.handle(Map.of("minDaysStale", 365));
        assertThat(result.isError()).isFalse();

        String json = ((TextContent) result.content().getFirst()).text();
        assertThat(json).contains("Complex.java");
        assertThat(json).contains("Simple.java");
    }

    @Test
    void noFilesReturned_whenMinDaysExceedsAge() {
        // Commits are ~790 days old; 1000-day threshold excludes them all
        CallToolResult result = tool.handle(Map.of("minDaysStale", 1000));
        assertThat(result.isError()).isFalse();

        String json = ((TextContent) result.content().getFirst()).text();
        assertThat(json).isEqualTo("[]");
    }

    @Test
    void complexFileRanksAboveSimpleFile() {
        // Both files share the same lastCommitMs (single commit), so norm(daysSince) = 1.0
        // for both (the normalise() sentinel for max == min). Ranking is then driven purely
        // by norm(complexity): Complex.java (cyclo=3) > Simple.java (cyclo=1).
        CallToolResult result = tool.handle(Map.of("minDaysStale", 365));
        String json = ((TextContent) result.content().getFirst()).text();

        int complexIdx = json.indexOf("Complex.java");
        int simpleIdx  = json.indexOf("Simple.java");
        assertThat(complexIdx).isGreaterThanOrEqualTo(0);
        assertThat(simpleIdx).isGreaterThanOrEqualTo(0);
        assertThat(complexIdx).isLessThan(simpleIdx);
    }

    @Test
    void extensionFilter_limitsResults() {
        // Test repo has no .ts files — extension filter should return empty
        CallToolResult result = tool.handle(Map.of("minDaysStale", 365, "extension", ".ts"));
        assertThat(result.isError()).isFalse();

        String json = ((TextContent) result.content().getFirst()).text();
        assertThat(json).isEqualTo("[]");
    }
}
