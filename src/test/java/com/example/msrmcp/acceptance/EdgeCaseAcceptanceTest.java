package com.example.msrmcp.acceptance;

import com.example.msrmcp.db.*;
import com.example.msrmcp.helper.TestRepoBuilder;
import com.example.msrmcp.index.Indexer;
import com.example.msrmcp.model.IndexResult;
import com.example.msrmcp.tool.GetTemporalCouplingTool;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edge-case acceptance tests that don't fit neatly into a single tool's test class.
 *
 * <p>Each test method is fully self-contained (setup → index → assert → cleanup).
 */
class EdgeCaseAcceptanceTest {

    /**
     * A file that was committed and later deleted must still appear in the
     * change history, but must not cause LocCounter to crash and must not
     * produce a file_metrics entry (the file no longer exists on disk).
     */
    @Test
    void deletedFile_trackedInHistory_butNotInMetrics() throws Exception {
        Path repoDir = new TestRepoBuilder()
                .commit("add: Temp.java", "src/Temp.java", "public class Temp {}")
                .build();
        try {
            // Delete the file and commit the deletion
            try (Git git = Git.open(repoDir.toFile())) {
                git.rm().addFilepattern("src/Temp.java").call();
                PersonIdent author = new PersonIdent("Test Author", "test@example.com");
                git.commit()
                        .setMessage("delete: remove Temp.java")
                        .setAuthor(author).setCommitter(author)
                        .call();
            }

            Files.createDirectories(repoDir.resolve(".msr"));
            Database db = Database.open(repoDir.resolve(".msr/msr.db"));
            CommitDao commitDao         = db.attach(CommitDao.class);
            FileChangeDao fileChangeDao = db.attach(FileChangeDao.class);
            FileMetricsDao metricsDao   = db.attach(FileMetricsDao.class);

            IndexResult result = Indexer.runFull(repoDir, db);

            assertThat(result.status()).isEqualTo("ok");
            assertThat(commitDao.count()).isEqualTo(2);

            // File must appear in change history (both ADD and DELETE commits)
            List<String> hashes = fileChangeDao.findCommitHashesForFile("src/Temp.java", null, 10);
            assertThat(hashes).isNotEmpty();

            // File must NOT appear in file_metrics (no longer on disk)
            assertThat(metricsDao.findByPaths(List.of("src/Temp.java"))).isEmpty();
        } finally {
            TestRepoBuilder.deleteRecursively(repoDir);
        }
    }

    /**
     * Binary files (detected via null-byte) must be silently skipped by
     * LocCounter. They must appear in the change history but not in file_metrics.
     * Adjacent text files must not be affected.
     */
    @Test
    void binaryFile_isSkippedByLocCounter() throws Exception {
        Path repoDir = new TestRepoBuilder()
                .commit("init", "src/Main.java", "public class Main {}")
                .build();
        try {
            // Commit a binary file (contains a null byte → detected as binary)
            try (Git git = Git.open(repoDir.toFile())) {
                Path binFile = repoDir.resolve("assets/logo.bin");
                Files.createDirectories(binFile.getParent());
                Files.write(binFile, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x00, 0x01, 0x02, 0x03});
                git.add().addFilepattern("assets/logo.bin").call();
                PersonIdent author = new PersonIdent("Test Author", "test@example.com");
                git.commit()
                        .setMessage("chore: add binary asset")
                        .setAuthor(author).setCommitter(author)
                        .call();
            }

            Files.createDirectories(repoDir.resolve(".msr"));
            Database db = Database.open(repoDir.resolve(".msr/msr.db"));
            FileChangeDao fileChangeDao = db.attach(FileChangeDao.class);
            FileMetricsDao metricsDao   = db.attach(FileMetricsDao.class);

            IndexResult result = Indexer.runFull(repoDir, db);

            assertThat(result.status()).isEqualTo("ok");

            // Binary file IS tracked in change history
            assertThat(fileChangeDao.findCommitHashesForFile("assets/logo.bin", null, 10))
                    .isNotEmpty();

            // Binary file is NOT in file_metrics
            assertThat(metricsDao.findByPaths(List.of("assets/logo.bin"))).isEmpty();

            // The text file next to it IS in file_metrics
            assertThat(metricsDao.findByPaths(List.of("src/Main.java"))).isNotEmpty();
        } finally {
            TestRepoBuilder.deleteRecursively(repoDir);
        }
    }

    /**
     * The coupling formula is co_changes / MIN(totalA, totalB).
     * A pair with independent commits on both sides has a low ratio and must be
     * filtered out when minCoupling is raised.
     *
     * <p>Repo design:
     * <ul>
     *   <li>Strong pair: C and D always change together (3 commits) → ratio=1.0
     *   <li>Weak pair: A and B each have 2 solo commits + 1 shared commit → ratio=1/3≈0.33
     * </ul>
     */
    @Test
    void minCoupling_threshold_excludesWeakPairs() throws Exception {
        Path repoDir = new TestRepoBuilder()
                // weak pair: A and B have solo commits → ratio = 1/MIN(3,3) = 0.33
                .commit("a1", "src/A.java", "v1")
                .commit("b1", "src/B.java", "v1")
                .commit("a2", "src/A.java", "v2")
                .commit("b2", "src/B.java", "v2")
                .commit("ab", Map.of("src/A.java", "v3", "src/B.java", "v3"))
                // strong pair: C and D always co-change → ratio = 3/MIN(3,3) = 1.0
                .commit("cd1", Map.of("src/C.java", "v1", "src/D.java", "v1"))
                .commit("cd2", Map.of("src/C.java", "v2", "src/D.java", "v2"))
                .commit("cd3", Map.of("src/C.java", "v3", "src/D.java", "v3"))
                .build();
        try {
            Files.createDirectories(repoDir.resolve(".msr"));
            Database db = Database.open(repoDir.resolve(".msr/msr.db"));
            Indexer.runFull(repoDir, db);

            GetTemporalCouplingTool tool = new GetTemporalCouplingTool(
                    db.attach(FileCouplingDao.class),
                    db.attach(FileChangeDao.class));

            // threshold 0.5 keeps C-D (1.0) but drops A-B (≈0.33)
            String json = ((TextContent) tool.handle(
                    Map.of("topN", 10, "minCoupling", 0.5)).content().getFirst()).text();
            assertThat(json).contains("C.java");
            assertThat(json).contains("D.java");
            assertThat(json).contains("\"couplingRatio\":1.0");
            assertThat(json).doesNotContain("A.java");
            assertThat(json).doesNotContain("B.java");
        } finally {
            TestRepoBuilder.deleteRecursively(repoDir);
        }
    }
}
