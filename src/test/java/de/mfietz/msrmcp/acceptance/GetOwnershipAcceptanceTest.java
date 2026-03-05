package de.mfietz.msrmcp.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.mfietz.msrmcp.db.*;
import de.mfietz.msrmcp.helper.TestRepoBuilder;
import de.mfietz.msrmcp.index.Indexer;
import de.mfietz.msrmcp.tool.GetOwnershipTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.*;

/**
 * Acceptance tests for the get_ownership tool.
 *
 * <p>Test repo layout:
 *
 * <ul>
 *   <li>alice@example.com makes 3 commits to Main.java → 75% ownership by commits
 *   <li>bob@example.com makes 1 commit to Main.java → 25%
 *   <li>bob@example.com makes 2 commits to Helper.java → 100%
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetOwnershipAcceptanceTest {

    static final String MAIN_V1 = "public class Main { void v1() {} }";
    static final String MAIN_V2 = "public class Main { void v1() {} void v2() {} }";
    static final String MAIN_V3 = "public class Main { void v1() {} void v2() {} void v3() {} }";
    static final String MAIN_V4 =
            "public class Main { void v1() {} void v2() {} void v3() {} void v4() {} }";
    static final String HELPER = "public class Helper {}";
    static final String HELPER2 = "public class Helper { void help() {} }";

    Path repoDir;
    Database db;
    GetOwnershipTool ownershipTool;

    @BeforeAll
    void setUp() throws Exception {
        repoDir =
                new TestRepoBuilder()
                        .commit("init", "src/Main.java", MAIN_V1, "Alice", "alice@example.com")
                        .commit("feat v2", "src/Main.java", MAIN_V2, "Alice", "alice@example.com")
                        .commit("feat v3", "src/Main.java", MAIN_V3, "Alice", "alice@example.com")
                        .commit("feat v4", "src/Main.java", MAIN_V4, "Bob", "bob@example.com")
                        .commit("helper", "src/Helper.java", HELPER, "Bob", "bob@example.com")
                        .commit("helper2", "src/Helper.java", HELPER2, "Bob", "bob@example.com")
                        .build();

        Files.createDirectories(repoDir.resolve(".msr"));
        db = Database.open(repoDir.resolve(".msr/msr.db"));

        Indexer.runFull(repoDir, db);

        CommitDao commitDao = db.attach(CommitDao.class);
        ownershipTool = new GetOwnershipTool(commitDao);
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void ownershipByCommits_mainJava_aliceOwns75Percent() {
        CallToolResult result = ownershipTool.handle(Map.of("topN", 10, "ownershipBy", "commits"));
        String json = ((TextContent) result.content().getFirst()).text();

        assertThat(json).contains("alice@example.com");
        assertThat(json).contains("src/Main.java");
        assertThat(json).contains("0.75");
    }

    @Test
    void ownershipByCommits_helperJava_bobOwns100Percent() {
        CallToolResult result = ownershipTool.handle(Map.of("topN", 10, "ownershipBy", "commits"));
        String json = ((TextContent) result.content().getFirst()).text();

        assertThat(json).contains("bob@example.com");
        assertThat(json).contains("src/Helper.java");
        assertThat(json).contains("1.0");
    }

    @Test
    void minOwnership_filtersLowOwnership() {
        CallToolResult result =
                ownershipTool.handle(
                        Map.of("topN", 10, "ownershipBy", "commits", "minOwnership", 0.9));
        String json = ((TextContent) result.content().getFirst()).text();

        assertThat(json).contains("src/Helper.java");
        assertThat(json).doesNotContain("src/Main.java");
    }

    @Test
    void ownershipByLines_isSupported() {
        CallToolResult result = ownershipTool.handle(Map.of("topN", 10, "ownershipBy", "lines"));
        assertThat(result.isError()).isFalse();
        String json = ((TextContent) result.content().getFirst()).text();
        assertThat(json).contains("ownershipRatio");
    }

    @Test
    void invalidOwnershipBy_returnsError() {
        CallToolResult result = ownershipTool.handle(Map.of("ownershipBy", "banana"));
        assertThat(result.isError()).isTrue();
    }
}
