package com.example.msrmcp.acceptance;

import com.example.msrmcp.db.*;
import com.example.msrmcp.helper.TestRepoBuilder;
import com.example.msrmcp.index.Indexer;
import com.example.msrmcp.model.IndexResult;
import com.example.msrmcp.tool.GetHotspotsTool;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a repository with exactly one commit (no parent) is handled
 * correctly. The root-commit / EmptyTreeIterator code path was listed as a
 * medium-severity risk in the design; this test guards against regressions.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RootCommitAcceptanceTest {

    Path repoDir;
    Database db;
    CommitDao commitDao;
    FileChangeDao fileChangeDao;
    FileMetricsDao fileMetricsDao;

    @BeforeAll
    void setUp() throws Exception {
        repoDir = new TestRepoBuilder()
                .commit("feat: init", "src/Main.java",
                        "public class Main { public static void main(String[] a) {} }")
                .build();
        Files.createDirectories(repoDir.resolve(".msr"));
        db = Database.open(repoDir.resolve(".msr/msr.db"));
        commitDao      = db.attach(CommitDao.class);
        fileChangeDao  = db.attach(FileChangeDao.class);
        fileMetricsDao = db.attach(FileMetricsDao.class);
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    @Order(1)
    void fullIndex_succeedsOnSingleCommitRepo() {
        IndexResult result = Indexer.runFull(repoDir, db);
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.commitsProcessed()).isEqualTo(1);
        assertThat(commitDao.count()).isEqualTo(1);
    }

    @Test
    @Order(2)
    void incrementalAfterFull_processesZeroNewCommits() {
        IndexResult result = Indexer.runIncremental(repoDir, db);
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.commitsProcessed()).isEqualTo(0);
        assertThat(commitDao.count()).isEqualTo(1); // unchanged
    }

    @Test
    @Order(3)
    void hotspots_returnResultForSingleCommit() {
        GetHotspotsTool tool = new GetHotspotsTool(fileChangeDao, fileMetricsDao);
        String json = ((TextContent) tool.handle(Map.of("topN", 5)).content().getFirst()).text();
        assertThat(json).contains("Main.java");
        assertThat(json).contains("\"changeFrequency\":1");
    }
}
