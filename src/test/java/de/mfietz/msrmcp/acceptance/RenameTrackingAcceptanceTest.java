package de.mfietz.msrmcp.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.mfietz.msrmcp.db.*;
import de.mfietz.msrmcp.helper.TestRepoBuilder;
import de.mfietz.msrmcp.index.Indexer;
import de.mfietz.msrmcp.tool.GetHotspotsTool;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;

/**
 * Verifies that file renames (same basename, different directory) are detected and history is
 * merged into the new path.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RenameTrackingAcceptanceTest {

    static final String V1 = "public class Bar { void a() {} }";
    static final String V2 = "public class Bar { void a() {} void b() {} }";

    Path repoDir;
    Database db;
    FileDao fileDao;
    FileChangeDao fileChangeDao;

    @BeforeAll
    void setUp() throws Exception {
        repoDir = new TestRepoBuilder().commit("feat: add Bar", "foo/Bar.java", V1).build();

        // Commit 2: rename foo/Bar.java → bar/Bar.java
        TestRepoBuilder.appendRename(
                repoDir, "refactor: move Bar to bar package", "foo/Bar.java", "bar/Bar.java", V1);

        // Commit 3: modify bar/Bar.java
        TestRepoBuilder.appendCommit(repoDir, "feat: add method b", "bar/Bar.java", V2);

        Files.createDirectories(repoDir.resolve(".msr"));
        db = Database.open(repoDir.resolve(".msr/msr.db"));
        fileDao = db.attach(FileDao.class);
        fileChangeDao = db.attach(FileChangeDao.class);

        Indexer.runFull(repoDir, db);
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void renamedFile_hasFullHistoryUnderNewPath() {
        List<String> allPaths = fileDao.findAllPaths();
        assertThat(allPaths).contains("bar/Bar.java");
        assertThat(allPaths).doesNotContain("foo/Bar.java");
    }

    @Test
    void renamedFile_changeCountIncludesPreRenameCommits() {
        List<String> distinctPaths = fileChangeDao.findDistinctPaths();
        assertThat(distinctPaths).contains("bar/Bar.java");
        assertThat(distinctPaths).doesNotContain("foo/Bar.java");

        var hotspotsTool = new GetHotspotsTool(fileChangeDao, db.attach(FileMetricsDao.class));
        String json =
                ((TextContent)
                                hotspotsTool
                                        .handle(Map.of("topN", 5, "extension", ".java"))
                                        .content()
                                        .getFirst())
                        .text();
        assertThat(json).contains("bar/Bar.java");
        assertThat(json).contains("\"changeFrequency\":3");
        assertThat(json).doesNotContain("foo/Bar.java");
    }
}
