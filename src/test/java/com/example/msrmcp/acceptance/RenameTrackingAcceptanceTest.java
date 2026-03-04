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
 * Acceptance tests for rename tracking.
 *
 * <p>Test repo: A.java created and modified in two commits, then renamed to B.java.
 * After indexing, all history should be queryable under B.java and A.java should be gone.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RenameTrackingAcceptanceTest {

    private Path repoDir;
    private FileChangeDao fileChangeDao;

    @BeforeAll
    void setUp() throws Exception {
        // Build repo with two commits on A.java
        repoDir = new TestRepoBuilder()
                .commit("feat: add A", "src/A.java",
                        "public class A { void methodA() {} }\n")
                .commit("fix: update A", "src/A.java",
                        "public class A { void methodA() {} void extra() {} }\n")
                .build();

        // Rename A.java → B.java via JGit (copy + rm + add)
        Path oldFile = repoDir.resolve("src/A.java");
        Path newFile = repoDir.resolve("src/B.java");
        Files.copy(oldFile, newFile);   // same content → JGit detects as RENAME
        Files.delete(oldFile);
        try (Git git = Git.open(repoDir.toFile())) {
            git.rm().addFilepattern("src/A.java").call();
            git.add().addFilepattern("src/B.java").call();
            PersonIdent author = new PersonIdent("Test Author", "test@example.com");
            git.commit()
                    .setMessage("refactor: rename A to B")
                    .setAuthor(author).setCommitter(author)
                    .call();
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
    void renamedFile_carriesForwardHistory() {
        // B.java should have commits from when it was still A.java
        List<String> hashes = fileChangeDao.findCommitHashesForFile("src/B.java", null, null, 100);
        // At least: initial commit (as A), update commit (as A), rename commit (as B)
        assertThat(hashes).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void renamedFile_oldPathGone() {
        // A.java should no longer have any tracked history
        List<String> hashes = fileChangeDao.findCommitHashesForFile("src/A.java", null, null, 100);
        assertThat(hashes).isEmpty();
    }

    @Test
    void renamedFile_changeFrequency_includesPreRenameCommits() {
        // findTopChangedFiles should report B.java with frequency ≥ 2
        List<FileChangeDao.FileChangeFrequencyRow> rows =
                fileChangeDao.findTopChangedFiles(null, "%", "%", 10);
        FileChangeDao.FileChangeFrequencyRow bRow = rows.stream()
                .filter(r -> r.filePath().equals("src/B.java"))
                .findFirst().orElse(null);
        assertThat(bRow).isNotNull();
        assertThat(bRow.changeFrequency()).isGreaterThanOrEqualTo(2);
    }

    // Gap #3: churn (lines_added/deleted) follows the rename
    @Test
    void renamedFile_churnIncludesPreRenameCommits() {
        List<FileChangeDao.ChurnRow> rows = fileChangeDao.findTopChurn(null, "%", "%", 10);
        // B.java must appear with lines_added accumulated from before the rename
        FileChangeDao.ChurnRow bRow = rows.stream()
                .filter(r -> r.filePath().equals("src/B.java"))
                .findFirst().orElse(null);
        assertThat(bRow).isNotNull();
        assertThat(bRow.linesAdded()).isGreaterThan(0);
        // A.java must no longer appear
        assertThat(rows.stream().anyMatch(r -> r.filePath().equals("src/A.java"))).isFalse();
    }
}
