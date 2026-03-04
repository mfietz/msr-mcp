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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gap #1: coupling data must follow a rename.
 *
 * <p>Repo: A.java and C.java co-change in two commits, then A is renamed to B.
 * After indexing, B.java must show coupling with C.java;
 * querying A.java must return no coupling partners.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RenameCouplingAcceptanceTest {

    private Path repoDir;
    private FileCouplingDao fileCouplingDao;

    @BeforeAll
    void setUp() throws Exception {
        repoDir = new TestRepoBuilder()
                .commit("init", Map.of(
                        "src/A.java", "class A {}\n",
                        "src/C.java", "class C {}\n"))
                .commit("feat: co-change A+C", Map.of(
                        "src/A.java", "class A { void m(){} }\n",
                        "src/C.java", "class C { void m(){} }\n"))
                .build();

        // Rename A.java → B.java
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

        Path dbPath = repoDir.resolve(".msr/msr.db");
        Files.createDirectories(dbPath.getParent());
        Database db = Database.open(dbPath);
        Indexer.runFull(repoDir, db);
        fileCouplingDao = db.attach(FileCouplingDao.class);
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void renamedFile_couplingFollowsNewName() {
        List<FileCouplingDao.PartnerRow> partners =
                fileCouplingDao.findTopCoupledForFile("src/B.java", 0.0, 10);
        assertThat(partners)
                .extracting(FileCouplingDao.PartnerRow::partnerPath)
                .contains("src/C.java");
    }

    @Test
    void oldName_hasNoCouplingPartners() {
        // A.java was removed from the files table — query must return empty
        List<FileCouplingDao.PartnerRow> partners =
                fileCouplingDao.findTopCoupledForFile("src/A.java", 0.0, 10);
        assertThat(partners).isEmpty();
    }
}
