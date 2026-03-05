package de.mfietz.msrmcp.acceptance;

import de.mfietz.msrmcp.db.*;
import de.mfietz.msrmcp.helper.TestRepoBuilder;
import de.mfietz.msrmcp.index.Indexer;
import de.mfietz.msrmcp.model.IndexResult;
import de.mfietz.msrmcp.tool.GetHotspotsTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that TypeScript files are indexed and appear in hotspots.
 *
 * <p>LocCounter must populate linesOfCode for .ts files; PMD skips them so
 * cyclomaticComplexity stays -1, but hotspotScore must be > 0 via the LOC fallback.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TypeScriptHotspotsAcceptanceTest {

    static final String APP_TS = """
            export function greet(name: string): string {
                if (!name) {
                    return "Hello, world!";
                }
                return `Hello, ${name}!`;
            }
            """;

    Path repoDir;
    GetHotspotsTool hotspotsTool;

    @BeforeAll
    void setUp() throws Exception {
        repoDir = new TestRepoBuilder()
                .commit("feat: add greeting", "src/app.ts", APP_TS)
                .commit("fix: empty name",    "src/app.ts", APP_TS.replace("!name", "name.length === 0"))
                .build();

        Files.createDirectories(repoDir.resolve(".msr"));
        Database db = Database.open(repoDir.resolve(".msr/msr.db"));

        IndexResult result = Indexer.runFull(repoDir, db);
        assertThat(result.status()).isEqualTo("ok");

        hotspotsTool = new GetHotspotsTool(
                db.attach(FileChangeDao.class),
                db.attach(FileMetricsDao.class));
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void typescriptFile_appearsInHotspots() {
        CallToolResult result = hotspotsTool.handle(Map.of("topN", 5, "extension", ".ts"));
        assertThat(result.isError()).isFalse();
        String json = ((TextContent) result.content().getFirst()).text();
        assertThat(json).contains("app.ts");
    }

    @Test
    void typescriptFile_hasLocPopulated() {
        CallToolResult result = hotspotsTool.handle(Map.of("topN", 1, "extension", ".ts"));
        String json = ((TextContent) result.content().getFirst()).text();
        // LocCounter must have counted lines
        assertThat(json).doesNotContain("\"linesOfCode\":0");
    }

    @Test
    void typescriptFile_hasNoComplexity_butPositiveScore() {
        CallToolResult result = hotspotsTool.handle(Map.of("topN", 1, "extension", ".ts"));
        String json = ((TextContent) result.content().getFirst()).text();
        // PMD does not compute cyclomatic complexity for TypeScript
        assertThat(json).contains("\"cyclomaticComplexity\":-1");
        // LOC fallback in HotspotScorer must yield a score > 0
        assertThat(json).doesNotContain("\"hotspotScore\":0.0");
    }
}
