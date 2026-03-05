package com.example.msrmcp.index;

import com.example.msrmcp.db.*;
import com.example.msrmcp.db.FileMetricsDao.FileMetricsIdRecord;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the two-phase PmdRunner API:
 * {@link PmdRunner#collectMetrics()} collects results without writing to DB,
 * {@link PmdRunner#writeBatch(List)} writes the collected results.
 */
class PmdRunnerTest {

    static final String JAVA_SOURCE = """
            public class Sample {
                public int compute(int x) {
                    if (x > 0) {
                        return x * 2;
                    } else {
                        return -x;
                    }
                }
            }
            """;

    Path repoDir;
    Database db;
    FileMetricsDao fileMetricsDao;
    FileDao fileDao;

    @BeforeEach
    void setUp() throws Exception {
        repoDir = Files.createTempDirectory("pmd-runner-test-");
        Path javaFile = repoDir.resolve("src/Sample.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, JAVA_SOURCE);

        Path dbPath = repoDir.resolve("test.db");
        db = Database.open(dbPath);
        fileMetricsDao = db.attach(FileMetricsDao.class);
        fileDao = db.attach(FileDao.class);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var stream = Files.walk(repoDir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
        }
    }

    @Test
    void collectMetrics_returnsJavaMetrics_withoutWritingToDatabase() {
        PmdRunner runner = new PmdRunner(repoDir, fileMetricsDao, fileDao);

        List<FileMetricsIdRecord> metrics = runner.collectMetrics();

        assertThat(metrics).isNotEmpty();
        assertThat(metrics).anyMatch(m -> m.cyclomaticComplexity() > 0);
        // Nothing written to DB yet
        assertThat(fileMetricsDao.count()).isEqualTo(0);
    }

    @Test
    void writeBatch_persistsCollectedMetrics() {
        PmdRunner runner = new PmdRunner(repoDir, fileMetricsDao, fileDao);
        List<FileMetricsIdRecord> metrics = runner.collectMetrics();

        runner.writeBatch(metrics);

        assertThat(fileMetricsDao.count()).isEqualTo(metrics.size());
    }

    @Test
    void collectThenWrite_producesCorrectCycloForJavaFile() {
        PmdRunner runner = new PmdRunner(repoDir, fileMetricsDao, fileDao);

        List<FileMetricsIdRecord> metrics = runner.collectMetrics();
        runner.writeBatch(metrics);

        // Sample.java has one method with if/else → cyclo >= 2
        assertThat(fileMetricsDao.count()).isGreaterThan(0);
        assertThat(metrics).allMatch(m -> m.cyclomaticComplexity() > 1);
    }
}
