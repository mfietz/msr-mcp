package de.mfietz.msrmcp.index;

import de.mfietz.msrmcp.db.*;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LocCounter} line-counting behaviour.
 *
 * <p>Tests run against a temp directory with no git history — only
 * {@code count(Set<String>)} is exercised since it doesn't need file_changes.
 */
class LocCounterTest {

    Path repoDir;
    Database db;
    FileMetricsDao fileMetricsDao;
    FileDao fileDao;
    LocCounter locCounter;

    @BeforeEach
    void setUp() throws Exception {
        repoDir = Files.createTempDirectory("loc-counter-test-");
        db = Database.open(repoDir.resolve("test.db"));
        fileMetricsDao = db.attach(FileMetricsDao.class);
        fileDao        = db.attach(FileDao.class);
        // FileChangeDao not needed for count(Set<String>)
        locCounter = new LocCounter(repoDir, null, fileMetricsDao, fileDao);
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
    void textFile_countsLines() throws Exception {
        writeFile("hello.txt", "line1\nline2\nline3\n");

        locCounter.count(Set.of("hello.txt"));

        assertThat(fileMetricsDao.count()).isEqualTo(1);
    }

    @Test
    void emptyFile_reportsZeroLoc() throws Exception {
        writeFile("empty.txt", "");

        locCounter.count(Set.of("empty.txt"));

        assertThat(fileMetricsDao.findAllFilePaths()).contains("empty.txt");
        // empty file → loc = 0
        var metrics = fileMetricsDao.findByPaths(java.util.List.of("empty.txt"));
        assertThat(metrics).hasSize(1);
        assertThat(metrics.get(0).loc()).isEqualTo(0);
    }

    @Test
    void fileWithNoTrailingNewline_countsCorrectly() throws Exception {
        writeFile("no-newline.txt", "line1\nline2");

        locCounter.count(Set.of("no-newline.txt"));

        var metrics = fileMetricsDao.findByPaths(java.util.List.of("no-newline.txt"));
        assertThat(metrics.get(0).loc()).isEqualTo(2);
    }

    @Test
    void binaryFile_isSkipped() throws Exception {
        // Write a file containing a null byte (binary indicator)
        Path file = repoDir.resolve("image.png");
        Files.write(file, new byte[]{0x50, 0x4E, 0x47, 0x00, 0x01, 0x02});

        locCounter.count(Set.of("image.png"));

        assertThat(fileMetricsDao.count()).isEqualTo(0);
    }

    @Test
    void nonExistentFile_isSkipped() {
        locCounter.count(Set.of("ghost.txt"));

        assertThat(fileMetricsDao.count()).isEqualTo(0);
    }

    @Test
    void windowsLineEndings_countSameAsUnixLineEndings() throws Exception {
        writeFile("windows.txt", "line1\r\nline2\r\nline3\r\n");
        writeFile("unix.txt",    "line1\nline2\nline3\n");

        locCounter.count(Set.of("windows.txt", "unix.txt"));

        var wm = fileMetricsDao.findByPaths(java.util.List.of("windows.txt"));
        var um = fileMetricsDao.findByPaths(java.util.List.of("unix.txt"));
        assertThat(wm.get(0).loc()).isEqualTo(um.get(0).loc());
    }

    private void writeFile(String relPath, String content) throws Exception {
        Path file = repoDir.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
