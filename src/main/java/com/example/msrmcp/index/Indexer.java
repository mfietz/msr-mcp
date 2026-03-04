package com.example.msrmcp.index;

import com.example.msrmcp.db.*;
import com.example.msrmcp.model.IndexResult;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Orchestrates git history indexing and static analysis.
 *
 * <p>{@link #runFull} rebuilds everything from scratch (use for {@code refresh_index}).
 * {@link #runIncremental} only processes commits newer than the last indexed tip;
 * it delegates to {@link #runFull} when the DB is empty.
 */
public final class Indexer {

    private Indexer() {}

    /**
     * Full re-index: clears coupling, walks the entire git history, runs
     * LocCounter and PmdRunner over all files.
     */
    public static IndexResult runFull(Path repoDir, Database db) {
        long start = System.currentTimeMillis();

        CommitDao       commitDao       = db.attach(CommitDao.class);
        FileChangeDao   fileChangeDao   = db.attach(FileChangeDao.class);
        FileMetricsDao  fileMetricsDao  = db.attach(FileMetricsDao.class);
        FileCouplingDao fileCouplingDao = db.attach(FileCouplingDao.class);
        FileDao         fileDao         = db.attach(FileDao.class);

        try {
            fileCouplingDao.deleteAll();

            System.err.println("MSR:   walking git history...");
            GitWalker.WalkResult walk = new GitWalker(repoDir, commitDao, fileChangeDao, fileCouplingDao, fileDao).walk();
            System.err.printf("MSR:   %,d commits done. counting lines of code...%n", walk.commitsProcessed());
            new LocCounter(repoDir, fileChangeDao, fileMetricsDao, fileDao).count();
            System.err.println("MSR:   running PMD analysis...");
            int files = new PmdRunner(repoDir, fileMetricsDao, fileDao).analyze();

            long duration = System.currentTimeMillis() - start;
            return new IndexResult("ok", files, walk.commitsProcessed(), duration, null);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return new IndexResult("error", 0, 0, duration, e.getMessage());
        }
    }

    /**
     * Incremental index: only processes commits newer than the already-indexed tip.
     * Falls back to {@link #runFull} when the DB is empty.
     *
     * <p>Because {@link FileCouplingDao#upsertBatch} accumulates co-change counts,
     * no coupling data needs to be cleared — new co-changes are added on top.
     */
    public static IndexResult runIncremental(Path repoDir, Database db) {
        long start = System.currentTimeMillis();

        CommitDao       commitDao       = db.attach(CommitDao.class);
        FileChangeDao   fileChangeDao   = db.attach(FileChangeDao.class);
        FileMetricsDao  fileMetricsDao  = db.attach(FileMetricsDao.class);
        FileCouplingDao fileCouplingDao = db.attach(FileCouplingDao.class);
        FileDao         fileDao         = db.attach(FileDao.class);

        try {
            Optional<String> latestHash = commitDao.findLatestHash();
            if (latestHash.isEmpty()) {
                // DB is empty — fall back to full index
                return runFull(repoDir, db);
            }

            System.err.println("MSR:   walking git history...");
            GitWalker.WalkResult walk = new GitWalker(repoDir, commitDao, fileChangeDao, fileCouplingDao, fileDao)
                    .walk(latestHash.get());

            if (walk.commitsProcessed() == 0) {
                long duration = System.currentTimeMillis() - start;
                return new IndexResult("ok", 0, 0, duration, null);
            }

            System.err.printf("MSR:   %,d new commits. counting lines of code...%n", walk.commitsProcessed());
            new LocCounter(repoDir, fileChangeDao, fileMetricsDao, fileDao).count(walk.changedPaths());
            System.err.println("MSR:   running PMD analysis...");
            int files = new PmdRunner(repoDir, fileMetricsDao, fileDao).analyze(walk.changedPaths());

            long duration = System.currentTimeMillis() - start;
            return new IndexResult("ok", files, walk.commitsProcessed(), duration, null);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return new IndexResult("error", 0, 0, duration, e.getMessage());
        }
    }
}
