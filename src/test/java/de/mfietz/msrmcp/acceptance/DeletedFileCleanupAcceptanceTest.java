package de.mfietz.msrmcp.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.mfietz.msrmcp.db.*;
import de.mfietz.msrmcp.helper.TestRepoBuilder;
import de.mfietz.msrmcp.index.Indexer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;

/**
 * Acceptance tests for stale file_metrics cleanup.
 *
 * <p>After a file is deleted from disk (committed delete), a subsequent full re-index must not
 * leave its entry in file_metrics.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeletedFileCleanupAcceptanceTest {

    private Path repoDir;
    private FileMetricsDao fileMetricsDao;

    @BeforeAll
    void setUp() throws Exception {
        repoDir =
                new TestRepoBuilder()
                        .commit(
                                "feat: add files",
                                java.util.Map.of(
                                        "src/Keep.java", "public class Keep {}\n",
                                        "src/Gone.java", "public class Gone {}\n"))
                        .build();

        // First index — both files get metrics
        Path dbPath = repoDir.resolve(".msr/msr.db");
        Files.createDirectories(dbPath.getParent());
        Database db = Database.open(dbPath);
        Indexer.runFull(repoDir, db);
        fileMetricsDao = db.attach(FileMetricsDao.class);

        // Delete Gone.java from disk (simulate deletion; no git commit needed)
        Files.delete(repoDir.resolve("src/Gone.java"));

        // Re-index (full) — cleanup should remove Gone.java's metrics
        Indexer.runFull(repoDir, db);
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void deletedFile_removedFromMetrics() {
        List<String> paths = fileMetricsDao.findAllFilePaths();
        assertThat(paths).doesNotContain("src/Gone.java");
    }

    @Test
    void survivingFile_retainsMetrics() {
        List<String> paths = fileMetricsDao.findAllFilePaths();
        assertThat(paths).contains("src/Keep.java");
    }
}
