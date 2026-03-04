package com.example.msrmcp.acceptance;

import com.example.msrmcp.db.*;
import com.example.msrmcp.helper.TestRepoBuilder;
import com.example.msrmcp.index.Indexer;
import com.example.msrmcp.model.IndexResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gap #4: incremental indexing must handle renames in new commits.
 *
 * <p>Flow: full index of 2 commits on A.java → add rename commit A→B → incremental index.
 * After incremental index, B.java must carry all 3 commits; A.java must be gone.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IncrementalRenameAcceptanceTest {

    private Path repoDir;
    private Database db;
    private FileChangeDao fileChangeDao;

    @BeforeAll
    void setUp() throws Exception {
        repoDir = new TestRepoBuilder()
                .commit("feat: add A", "src/A.java", "class A { void m(){} }\n")
                .commit("fix: update A", "src/A.java", "class A { void m(){} void n(){} }\n")
                .build();

        Path dbPath = repoDir.resolve(".msr/msr.db");
        Files.createDirectories(dbPath.getParent());
        db = Database.open(dbPath);
        fileChangeDao = db.attach(FileChangeDao.class);
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    @Order(1)
    void fullIndex_seesOnlyAJava() {
        IndexResult r = Indexer.runFull(repoDir, db);
        assertThat(r.commitsProcessed()).isEqualTo(2);
        assertThat(fileChangeDao.findCommitHashesForFile("src/A.java", null, null, 100)).hasSize(2);
    }

    @Test
    @Order(2)
    void incrementalIndex_afterRename_mergesHistory() throws Exception {
        // Add rename commit A → B
        Path aFile = repoDir.resolve("src/A.java");
        Path bFile = repoDir.resolve("src/B.java");
        Files.copy(aFile, bFile);
        Files.delete(aFile);
        try (Git git = Git.open(repoDir.toFile())) {
            git.rm().addFilepattern("src/A.java").call();
            git.add().addFilepattern("src/B.java").call();
            PersonIdent author = new PersonIdent("Test Author", "test@example.com");
            git.commit().setMessage("refactor: rename A to B")
                    .setAuthor(author).setCommitter(author).call();
        }

        IndexResult r = Indexer.runIncremental(repoDir, db);
        assertThat(r.commitsProcessed()).isEqualTo(1);

        // B.java must have all 3 commits (2 original + rename)
        List<String> bHashes = fileChangeDao.findCommitHashesForFile("src/B.java", null, null, 100);
        assertThat(bHashes).hasSize(3);

        // A.java must be gone
        List<String> aHashes = fileChangeDao.findCommitHashesForFile("src/A.java", null, null, 100);
        assertThat(aHashes).isEmpty();
    }
}
