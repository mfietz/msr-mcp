package de.mfietz.msrmcp.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.mfietz.msrmcp.db.*;
import de.mfietz.msrmcp.helper.TestRepoBuilder;
import de.mfietz.msrmcp.index.IndexTracker;
import de.mfietz.msrmcp.index.Indexer;
import de.mfietz.msrmcp.tool.GetIndexStatusTool;
import de.mfietz.msrmcp.tool.GetSummaryTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.*;

/**
 * Acceptance tests for the index-status feature.
 *
 * <p>Verifies that {@code get_index_status} reports the correct state and that analytics tools
 * block callers while indexing is in progress.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IndexStatusAcceptanceTest {

    Path repoDir;
    Database db;
    CommitDao commitDao;
    FileChangeDao fileChangeDao;
    FileMetricsDao fileMetricsDao;
    FileDao fileDao;

    @BeforeAll
    void setUp() throws Exception {
        repoDir =
                new TestRepoBuilder()
                        .commit("initial commit", "src/App.java", "public class App {}")
                        .build();

        Files.createDirectories(repoDir.resolve(".msr"));
        db = Database.open(repoDir.resolve(".msr/msr.db"));
        commitDao = db.attach(CommitDao.class);
        fileChangeDao = db.attach(FileChangeDao.class);
        fileMetricsDao = db.attach(FileMetricsDao.class);
        fileDao = db.attach(FileDao.class);

        // Populate DB so summary queries have data to return.
        Indexer.runFull(repoDir, db);
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    // ── GetIndexStatusTool ─────────────────────────────────────────────────

    @Test
    void getIndexStatus_returnsNotStarted_whenTrackerIsFresh() {
        IndexTracker tracker = new IndexTracker();

        CallToolResult result = new GetIndexStatusTool(tracker).handle(Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"status\":\"not_started\"");
    }

    @Test
    void getIndexStatus_returnsIndexing_whileRunning() {
        IndexTracker tracker = new IndexTracker();
        tracker.markIndexing();

        CallToolResult result = new GetIndexStatusTool(tracker).handle(Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"status\":\"indexing\"");
    }

    @Test
    void getIndexStatus_returnsReady_afterCompletion() {
        IndexTracker tracker = new IndexTracker();
        tracker.markIndexing();
        tracker.markReady(1234L);

        CallToolResult result = new GetIndexStatusTool(tracker).handle(Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"status\":\"ready\"");
        assertThat(text(result)).contains("\"elapsedMs\":1234");
    }

    @Test
    void getIndexStatus_returnsError_whenIndexingFailed() {
        IndexTracker tracker = new IndexTracker();
        tracker.markIndexing();
        tracker.markError("git walk failed");

        CallToolResult result = new GetIndexStatusTool(tracker).handle(Map.of());

        // The tool itself does not return isError — it reports the state as content.
        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"status\":\"error\"");
        assertThat(text(result)).contains("git walk failed");
    }

    @Test
    void getIndexStatus_includesStartedAtMs_whileIndexing() {
        IndexTracker tracker = new IndexTracker();
        long before = System.currentTimeMillis();
        tracker.markIndexing();

        String json = text(new GetIndexStatusTool(tracker).handle(Map.of()));

        assertThat(json).contains("\"startedAtMs\":");
        // startedAtMs must be a plausible timestamp, not zero.
        assertThat(json).doesNotContain("\"startedAtMs\":0");
        long after = System.currentTimeMillis();
        // Extract and verify it is within the test window.
        long startedAt = extractLong(json, "startedAtMs");
        assertThat(startedAt).isBetween(before, after);
    }

    // ── Analytics tools blocked during indexing ────────────────────────────

    @Test
    void summaryTool_returnsError_whileIndexingIsInProgress() {
        IndexTracker tracker = new IndexTracker();
        tracker.markIndexing();
        GetSummaryTool summary =
                new GetSummaryTool(commitDao, fileChangeDao, fileMetricsDao, fileDao, tracker);

        CallToolResult result = summary.handle(Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).containsIgnoringCase("index");
    }

    @Test
    void summaryTool_works_afterIndexingIsComplete() {
        IndexTracker tracker = new IndexTracker();
        tracker.markIndexing();
        tracker.markReady(100L);
        GetSummaryTool summary =
                new GetSummaryTool(commitDao, fileChangeDao, fileMetricsDao, fileDao, tracker);

        CallToolResult result = summary.handle(Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(text(result)).contains("\"totalCommits\":1");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String text(CallToolResult r) {
        return ((TextContent) r.content().getFirst()).text();
    }

    /** Extracts a numeric value for the given key from a flat JSON string. */
    private static long extractLong(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search) + search.length();
        int end = json.indexOf(',', start);
        if (end < 0) end = json.indexOf('}', start);
        return Long.parseLong(json.substring(start, end).trim());
    }
}
