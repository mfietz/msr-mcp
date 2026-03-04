package com.example.msrmcp.acceptance;

import com.example.msrmcp.db.*;
import com.example.msrmcp.helper.TestRepoBuilder;
import com.example.msrmcp.index.Indexer;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gap #2: rename chains (A→B→C) must resolve so all history ends up under the final name.
 *
 * <p>Repo: A.java created, then renamed to B.java, then renamed to C.java.
 * After indexing, C.java must have 3 commits; A.java and B.java must be gone.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RenameChainAcceptanceTest {

    private Path repoDir;
    private FileChangeDao fileChangeDao;

    @BeforeAll
    void setUp() throws Exception {
        repoDir = new TestRepoBuilder()
                .commit("feat: add A", "src/A.java", "class A { void m(){} }\n")
                .build();

        try (Git git = Git.open(repoDir.toFile())) {
            PersonIdent author = new PersonIdent("Test Author", "test@example.com");

            // Rename A → B
            Path aFile = repoDir.resolve("src/A.java");
            Path bFile = repoDir.resolve("src/B.java");
            Files.copy(aFile, bFile);
            Files.delete(aFile);
            git.rm().addFilepattern("src/A.java").call();
            git.add().addFilepattern("src/B.java").call();
            git.commit().setMessage("refactor: rename A to B")
                    .setAuthor(author).setCommitter(author).call();

            // Rename B → C
            Path cFile = repoDir.resolve("src/C.java");
            Files.copy(bFile, cFile);
            Files.delete(bFile);
            git.rm().addFilepattern("src/B.java").call();
            git.add().addFilepattern("src/C.java").call();
            git.commit().setMessage("refactor: rename B to C")
                    .setAuthor(author).setCommitter(author).call();
        }

        Path dbPath = repoDir.resolve(".msr/msr.db");
        Files.createDirectories(dbPath.getParent());
        Database db = Database.open(dbPath);
        Indexer.runFull(repoDir, db);
        fileChangeDao = db.attach(FileChangeDao.class);
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void renameChain_finalPath_hasFullHistory() {
        List<String> hashes = fileChangeDao.findCommitHashesForFile("src/C.java", null, null, 100);
        // create A + rename A→B + rename B→C = 3 commits
        assertThat(hashes).hasSize(3);
    }

    @Test
    void renameChain_intermediatePath_gone() {
        List<String> hashes = fileChangeDao.findCommitHashesForFile("src/B.java", null, null, 100);
        assertThat(hashes).isEmpty();
    }

    @Test
    void renameChain_originalPath_gone() {
        List<String> hashes = fileChangeDao.findCommitHashesForFile("src/A.java", null, null, 100);
        assertThat(hashes).isEmpty();
    }
}
