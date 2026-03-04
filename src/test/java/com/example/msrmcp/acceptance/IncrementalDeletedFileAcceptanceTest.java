package com.example.msrmcp.acceptance;

import com.example.msrmcp.db.*;
import com.example.msrmcp.helper.TestRepoBuilder;
import com.example.msrmcp.index.Indexer;
import com.example.msrmcp.model.IndexResult;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gap #5: incremental indexing must clean up file_metrics for files deleted via git commit.
 *
 * <p>Flow: full index of A.java + B.java → git-commit deletion of A.java → incremental index.
 * After incremental index, A.java must no longer appear in file_metrics;
 * B.java must retain its metrics.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IncrementalDeletedFileAcceptanceTest {

    private Path repoDir;
    private Database db;
    private FileMetricsDao fileMetricsDao;

    @BeforeAll
    void setUp() throws Exception {
        repoDir = new TestRepoBuilder()
                .commit("feat: add files", Map.of(
                        "src/A.java", "public class A {}\n",
                        "src/B.java", "public class B {}\n"))
                .build();

        Path dbPath = repoDir.resolve(".msr/msr.db");
        Files.createDirectories(dbPath.getParent());
        db = Database.open(dbPath);
        fileMetricsDao = db.attach(FileMetricsDao.class);
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    @Order(1)
    void fullIndex_bothFilesHaveMetrics() {
        IndexResult r = Indexer.runFull(repoDir, db);
        assertThat(r.status()).isEqualTo("ok");
        List<String> paths = fileMetricsDao.findAllFilePaths();
        assertThat(paths).contains("src/A.java", "src/B.java");
    }

    @Test
    @Order(2)
    void incrementalIndex_afterDeletion_removesMetrics() throws Exception {
        // Commit deletion of A.java
        TestRepoBuilder.appendDeletion(repoDir, "fix: remove A", "src/A.java");

        IndexResult r = Indexer.runIncremental(repoDir, db);
        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.commitsProcessed()).isEqualTo(1);

        List<String> paths = fileMetricsDao.findAllFilePaths();
        assertThat(paths).doesNotContain("src/A.java");
        assertThat(paths).contains("src/B.java");
    }
}
