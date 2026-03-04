package com.example.msrmcp.acceptance;

import com.example.msrmcp.db.*;
import com.example.msrmcp.helper.TestRepoBuilder;
import com.example.msrmcp.index.Indexer;
import com.example.msrmcp.tool.GetHotspotsTool;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance tests for the {@code pathFilter} parameter of {@code get_hotspots}.
 *
 * <p>Test repo layout:
 * <ul>
 *   <li>{@code src/service/Auth.java} — changed 3× (highest churn, service layer)
 *   <li>{@code src/util/StringUtils.java} — changed 1× (utility layer)
 *   <li>{@code test/AuthTest.java} — changed 2× (test layer)
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PathFilterAcceptanceTest {

    Path repoDir;
    GetHotspotsTool hotspotsTool;

    @BeforeAll
    void setUp() throws Exception {
        repoDir = new TestRepoBuilder()
                .commit("init", Map.of(
                        "src/service/Auth.java",     "class Auth {}",
                        "src/util/StringUtils.java", "class StringUtils {}",
                        "test/AuthTest.java",        "class AuthTest {}"))
                .commit("feat: auth v2", Map.of(
                        "src/service/Auth.java", "class Auth { void login() {} }",
                        "test/AuthTest.java",    "class AuthTest { void testLogin() {} }"))
                .commit("feat: auth v3",
                        "src/service/Auth.java", "class Auth { void login() {} void logout() {} }")
                .build();

        Files.createDirectories(repoDir.resolve(".msr"));
        Database db = Database.open(repoDir.resolve(".msr/msr.db"));
        Indexer.runFull(repoDir, db);
        hotspotsTool = new GetHotspotsTool(db.attach(FileChangeDao.class),
                                           db.attach(FileMetricsDao.class));
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void noFilter_returnsAllFiles() {
        String json = text(hotspotsTool.handle(Map.of("topN", 10)));
        assertThat(json).contains("Auth.java");
        assertThat(json).contains("StringUtils.java");
        assertThat(json).contains("AuthTest.java");
    }

    @Test
    void pathFilter_srcServiceOnly_excludesOtherDirs() {
        String json = text(hotspotsTool.handle(Map.of("topN", 10, "pathFilter", "src/service/%")));
        assertThat(json).contains("src/service/Auth.java");
        assertThat(json).doesNotContain("StringUtils.java");
        assertThat(json).doesNotContain("AuthTest.java");
    }

    @Test
    void pathFilter_testDir_returnsOnlyTestFiles() {
        String json = text(hotspotsTool.handle(Map.of("topN", 10, "pathFilter", "test/%")));
        assertThat(json).contains("AuthTest.java");
        assertThat(json).doesNotContain("Auth.java");
        assertThat(json).doesNotContain("StringUtils.java");
    }

    @Test
    void pathFilter_combined_withExtension() {
        // pathFilter=src/% AND extension=.java — should return Auth + StringUtils, not AuthTest
        // (AuthTest is in test/, not src/)
        String json = text(hotspotsTool.handle(
                Map.of("topN", 10, "pathFilter", "src/%", "extension", ".java")));
        assertThat(json).contains("Auth.java");
        assertThat(json).contains("StringUtils.java");
        assertThat(json).doesNotContain("AuthTest.java");
    }

    @Test
    void pathFilter_nonMatching_returnsEmpty() {
        String json = text(hotspotsTool.handle(Map.of("topN", 10, "pathFilter", "nonexistent/%")));
        assertThat(json).isEqualTo("[]");
    }

    private static String text(io.modelcontextprotocol.spec.McpSchema.CallToolResult r) {
        return ((TextContent) r.content().getFirst()).text();
    }
}
