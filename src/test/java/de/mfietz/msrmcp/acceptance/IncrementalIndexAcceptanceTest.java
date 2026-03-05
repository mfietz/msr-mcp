package de.mfietz.msrmcp.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.mfietz.msrmcp.db.*;
import de.mfietz.msrmcp.helper.TestRepoBuilder;
import de.mfietz.msrmcp.index.Indexer;
import de.mfietz.msrmcp.model.IndexResult;
import de.mfietz.msrmcp.tool.GetHotspotsTool;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.*;

/** Verifies incremental indexing: only new commits are processed. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IncrementalIndexAcceptanceTest {

    static final String V1 = "public class App { public void run() {} }";
    static final String V2 = "public class App { public void run() { System.out.println(); } }";
    static final String V3 =
            "public class App { public void run() { System.out.println(); System.exit(0); } }";

    Path repoDir;
    Database db;
    CommitDao commitDao;
    FileChangeDao fileChangeDao;
    FileMetricsDao fileMetricsDao;
    GetHotspotsTool hotspotsTool;
    TestRepoBuilder builder;

    @BeforeAll
    void setUp() throws Exception {
        builder =
                new TestRepoBuilder()
                        .commit("feat: v1", "src/App.java", V1)
                        .commit("fix: v2", "src/App.java", V2);

        repoDir = builder.build();
        Files.createDirectories(repoDir.resolve(".msr"));
        db = Database.open(repoDir.resolve(".msr/msr.db"));
        commitDao = db.attach(CommitDao.class);
        fileChangeDao = db.attach(FileChangeDao.class);
        fileMetricsDao = db.attach(FileMetricsDao.class);
        hotspotsTool = new GetHotspotsTool(fileChangeDao, fileMetricsDao);
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    @Order(1)
    void fullIndex_processesBothInitialCommits() {
        IndexResult result = Indexer.runFull(repoDir, db);
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.commitsProcessed()).isEqualTo(2);
        assertThat(commitDao.count()).isEqualTo(2);
    }

    @Test
    @Order(2)
    void incrementalIndex_withNoNewCommits_processesZero() {
        IndexResult result = Indexer.runIncremental(repoDir, db);
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.commitsProcessed()).isEqualTo(0);
        assertThat(commitDao.count()).isEqualTo(2); // unchanged
    }

    @Test
    @Order(3)
    void incrementalIndex_picksUpNewCommit() throws Exception {
        // Add a third commit directly to the repo on disk
        TestRepoBuilder.appendCommit(repoDir, "fix: v3", "src/App.java", V3);

        IndexResult result = Indexer.runIncremental(repoDir, db);
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.commitsProcessed()).isEqualTo(1);
        assertThat(commitDao.count()).isEqualTo(3);
    }

    @Test
    @Order(4)
    void afterIncremental_hotspots_reflectAllThreeCommits() {
        String json =
                ((TextContent)
                                hotspotsTool
                                        .handle(Map.of("topN", 1, "extension", ".java"))
                                        .content()
                                        .getFirst())
                        .text();
        assertThat(json).contains("\"changeFrequency\":3");
    }
}
