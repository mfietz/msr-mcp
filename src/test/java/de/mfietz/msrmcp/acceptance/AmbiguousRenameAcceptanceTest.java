package de.mfietz.msrmcp.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.mfietz.msrmcp.db.Database;
import de.mfietz.msrmcp.db.FileChangeDao;
import de.mfietz.msrmcp.db.FileDao;
import de.mfietz.msrmcp.helper.TestRepoBuilder;
import de.mfietz.msrmcp.index.Indexer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Verifies that two independent files sharing the same basename are NOT treated as a rename, even
 * when one is deleted and the other re-created in the same commit.
 *
 * <p>Regression test for: UNIQUE constraint failure when the rename target already exists in the
 * files table.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AmbiguousRenameAcceptanceTest {

    static final String CONTENT_A = "public class Foo { void a() {} }";
    static final String CONTENT_B = "public class Foo { void b() {} }";

    Path repoDir;
    Database db;
    FileDao fileDao;
    FileChangeDao fileChangeDao;

    @BeforeAll
    void setUp() throws Exception {
        // Commit 1: b/Foo.java created — gets its own file_id in files table
        repoDir = new TestRepoBuilder().commit("feat: add b/Foo", "b/Foo.java", CONTENT_B).build();

        // Commit 2: a/Foo.java created independently — different file, same basename
        TestRepoBuilder.appendCommit(repoDir, "feat: add a/Foo", "a/Foo.java", CONTENT_A);

        // Commit 3: delete b/Foo.java
        TestRepoBuilder.appendDeletion(repoDir, "refactor: remove b/Foo", "b/Foo.java");

        // Commit 4: delete a/Foo.java, re-create b/Foo.java in same commit.
        // Git diff shows: DELETE a/Foo.java + ADD b/Foo.java (same basename).
        // b/Foo.java is already in the files table → must NOT be treated as a rename.
        TestRepoBuilder.appendRename(
                repoDir, "refactor: swap Foo locations", "a/Foo.java", "b/Foo.java", CONTENT_B);

        Files.createDirectories(repoDir.resolve(".msr"));
        db = Database.open(repoDir.resolve(".msr/msr.db"));
        fileDao = db.attach(FileDao.class);
        fileChangeDao = db.attach(FileChangeDao.class);

        Indexer.runFull(repoDir, db);
    }

    @AfterAll
    void tearDown() throws Exception {
        if (db != null) db.close();
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void indexing_doesNotCrashOnAmbiguousBasename() {
        // Indexing already ran in @BeforeAll — if we reached this test, no exception was thrown.
        assertThat(fileDao.findAllPaths()).isNotEmpty();
    }

    @Test
    void bothFiles_retainIndependentHistories() {
        List<String> paths = fileDao.findAllPaths();
        assertThat(paths).contains("a/Foo.java", "b/Foo.java");

        List<String> changedPaths = fileChangeDao.findDistinctPaths();
        assertThat(changedPaths).contains("a/Foo.java", "b/Foo.java");
    }
}
