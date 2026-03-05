package de.mfietz.msrmcp.index;

import de.mfietz.msrmcp.db.*;
import de.mfietz.msrmcp.db.FileMetricsDao.FileMetricsIdRecord;
import de.mfietz.msrmcp.model.IndexResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Orchestrates git history indexing and static analysis.
 *
 * <p>{@link #runFull} rebuilds everything from scratch (use for {@code refresh_index}). {@link
 * #runIncremental} only processes commits newer than the last indexed tip; it delegates to {@link
 * #runFull} when the DB is empty.
 */
public final class Indexer {

    private Indexer() {}

    /**
     * Full re-index: clears coupling, walks the entire git history, runs LocCounter and PmdRunner
     * over all files.
     */
    public static IndexResult runFull(Path repoDir, Database db) {
        long start = System.currentTimeMillis();

        CommitDao commitDao = db.attach(CommitDao.class);
        FileChangeDao fileChangeDao = db.attach(FileChangeDao.class);
        FileMetricsDao fileMetricsDao = db.attach(FileMetricsDao.class);
        FileCouplingDao fileCouplingDao = db.attach(FileCouplingDao.class);
        FileDao fileDao = db.attach(FileDao.class);

        try {
            fileCouplingDao.deleteAll();
            db.dropAnalyticalIndexes();

            System.err.println("MSR:   walking git history...");
            GitWalker.WalkResult walk =
                    new GitWalker(repoDir, commitDao, fileChangeDao, fileCouplingDao, fileDao)
                            .walk();
            System.err.printf(
                    "MSR:   %,d commits done. rebuilding indexes...%n", walk.commitsProcessed());
            db.createAnalyticalIndexes();
            System.err.println("MSR:   running LOC + PMD in parallel...");
            LocCounter locCounter = new LocCounter(repoDir, fileChangeDao, fileMetricsDao, fileDao);
            PmdRunner pmdRunner = new PmdRunner(repoDir, fileMetricsDao, fileDao);

            ExecutorService exec = Executors.newFixedThreadPool(2);
            Future<Integer> locFuture = exec.submit(() -> locCounter.count());
            Future<List<FileMetricsIdRecord>> pmdFuture =
                    exec.submit(() -> pmdRunner.collectMetrics());
            exec.shutdown();

            locFuture.get();
            List<FileMetricsIdRecord> pmdBatch = pmdFuture.get();
            pmdRunner.writeBatch(pmdBatch);
            int files = pmdBatch.size();

            // Remove stale metrics for files deleted or renamed out of existence
            deleteStaleMetrics(repoDir, fileMetricsDao);

            long duration = System.currentTimeMillis() - start;
            return new IndexResult("ok", files, walk.commitsProcessed(), duration, null);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return new IndexResult("error", 0, 0, duration, e.getMessage());
        }
    }

    /**
     * Incremental index: only processes commits newer than the already-indexed tip. Falls back to
     * {@link #runFull} when the DB is empty.
     *
     * <p>Because {@link FileCouplingDao#upsertBatch} accumulates co-change counts, no coupling data
     * needs to be cleared — new co-changes are added on top.
     */
    public static IndexResult runIncremental(Path repoDir, Database db) {
        long start = System.currentTimeMillis();

        CommitDao commitDao = db.attach(CommitDao.class);
        FileChangeDao fileChangeDao = db.attach(FileChangeDao.class);
        FileMetricsDao fileMetricsDao = db.attach(FileMetricsDao.class);
        FileCouplingDao fileCouplingDao = db.attach(FileCouplingDao.class);
        FileDao fileDao = db.attach(FileDao.class);

        try {
            Optional<String> latestHash = commitDao.findLatestHash();
            if (latestHash.isEmpty()) {
                // DB is empty — fall back to full index
                return runFull(repoDir, db);
            }

            System.err.println("MSR:   walking git history...");
            GitWalker.WalkResult walk =
                    new GitWalker(repoDir, commitDao, fileChangeDao, fileCouplingDao, fileDao)
                            .walk(latestHash.get());

            if (walk.commitsProcessed() == 0) {
                long duration = System.currentTimeMillis() - start;
                return new IndexResult("ok", 0, 0, duration, null);
            }

            System.err.printf(
                    "MSR:   %,d new commits. running LOC + PMD in parallel...%n",
                    walk.commitsProcessed());
            LocCounter locCounter = new LocCounter(repoDir, fileChangeDao, fileMetricsDao, fileDao);
            PmdRunner pmdRunner = new PmdRunner(repoDir, fileMetricsDao, fileDao);

            ExecutorService exec = Executors.newFixedThreadPool(2);
            Future<Integer> locFuture = exec.submit(() -> locCounter.count(walk.changedPaths()));
            Future<List<FileMetricsIdRecord>> pmdFuture =
                    exec.submit(() -> pmdRunner.collectMetrics(walk.changedPaths()));
            exec.shutdown();

            locFuture.get();
            List<FileMetricsIdRecord> pmdBatch = pmdFuture.get();
            pmdRunner.writeBatch(pmdBatch);
            int files = pmdBatch.size();

            // Clean up metrics for any files deleted or renamed in the new commits
            List<String> gone =
                    walk.changedPaths().stream()
                            .filter(p -> !Files.exists(repoDir.resolve(p)))
                            .toList();
            if (!gone.isEmpty()) fileMetricsDao.deleteByPaths(gone);

            long duration = System.currentTimeMillis() - start;
            return new IndexResult("ok", files, walk.commitsProcessed(), duration, null);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return new IndexResult("error", 0, 0, duration, e.getMessage());
        }
    }

    private static void deleteStaleMetrics(Path repoDir, FileMetricsDao fileMetricsDao) {
        List<String> allPaths = fileMetricsDao.findAllFilePaths();
        List<String> stale =
                allPaths.stream().filter(p -> !Files.exists(repoDir.resolve(p))).toList();
        if (stale.isEmpty()) return;
        int chunkSize = 999;
        for (int i = 0; i < stale.size(); i += chunkSize) {
            fileMetricsDao.deleteByPaths(stale.subList(i, Math.min(i + chunkSize, stale.size())));
        }
    }
}
