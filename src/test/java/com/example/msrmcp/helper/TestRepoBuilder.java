package com.example.msrmcp.helper;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.StoredConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds an in-process temporary git repository with deterministic commits
 * for use in acceptance tests.
 *
 * <p>Usage:
 * <pre>{@code
 * Path repo = new TestRepoBuilder()
 *     .commit("feat: init", Map.of("src/Main.java", "public class Main {}"))
 *     .commit("fix: bug",   Map.of("src/Main.java", "public class Main { void m(){} }"))
 *     .build();
 * }</pre>
 */
public final class TestRepoBuilder {

    private record CommitSpec(String message, Map<String, String> files, Instant when) {}

    private final List<CommitSpec> commits = new ArrayList<>();
    private long baseEpochSec = Instant.parse("2024-01-01T00:00:00Z").getEpochSecond();

    /** Add a commit with a single file. */
    public TestRepoBuilder commit(String message, String filePath, String content) {
        return commit(message, Map.of(filePath, content));
    }

    /** Add a commit touching multiple files (map: relative-path → content). */
    public TestRepoBuilder commit(String message, Map<String, String> files) {
        commits.add(new CommitSpec(message, new LinkedHashMap<>(files),
                Instant.ofEpochSecond(baseEpochSec)));
        baseEpochSec += 3600; // 1 hour between commits
        return this;
    }

    /**
     * Creates the git repository in a temp directory and returns its root path.
     * Caller is responsible for deleting it after the test.
     */
    public Path build() throws Exception {
        Path dir = Files.createTempDirectory("msr-test-");
        Git git = Git.init().setDirectory(dir.toFile()).call();

        StoredConfig cfg = git.getRepository().getConfig();
        cfg.setString("user", null, "name", "Test Author");
        cfg.setString("user", null, "email", "test@example.com");
        cfg.save();

        PersonIdent author = new PersonIdent("Test Author", "test@example.com");

        for (CommitSpec spec : commits) {
            for (var entry : spec.files().entrySet()) {
                Path filePath = dir.resolve(entry.getKey());
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, entry.getValue());
                git.add().addFilepattern(entry.getKey()).call();
            }
            PersonIdent timed = new PersonIdent(author, spec.when());
            git.commit()
                    .setMessage(spec.message())
                    .setAuthor(timed)
                    .setCommitter(timed)
                    .call();
        }

        git.close();
        return dir;
    }

    /**
     * Appends a single commit to an already-built repo on disk.
     * Used by incremental-indexing tests to simulate a {@code git pull}.
     */
    public static void appendCommit(Path repoDir, String message, String filePath, String content)
            throws Exception {
        try (Git git = Git.open(repoDir.toFile())) {
            StoredConfig cfg = git.getRepository().getConfig();
            cfg.setString("user", null, "name", "Test Author");
            cfg.setString("user", null, "email", "test@example.com");
            cfg.save();

            Path file = repoDir.resolve(filePath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            git.add().addFilepattern(filePath).call();

            PersonIdent author = new PersonIdent("Test Author", "test@example.com");
            git.commit().setMessage(message).setAuthor(author).setCommitter(author).call();
        }
    }

    /** Recursively delete a directory (for @AfterAll cleanup). */
    public static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
        }
    }
}
