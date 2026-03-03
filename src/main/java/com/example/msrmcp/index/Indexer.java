package com.example.msrmcp.index;

import com.example.msrmcp.db.*;
import com.example.msrmcp.model.IndexResult;

import java.nio.file.Path;

/**
 * Orchestrates a full re-index: clears co-change data, walks git history,
 * then runs PMD analysis. Commit/file-change records are upserted (INSERT OR IGNORE)
 * so they survive a partial re-index.
 */
public final class Indexer {

    private Indexer() {}

    /**
     * Performs a full index of the repository at {@code repoDir}.
     *
     * @return summary result record
     */
    public static IndexResult runFull(Path repoDir, Database db) {
        long start = System.currentTimeMillis();

        CommitDao       commitDao       = db.attach(CommitDao.class);
        FileChangeDao   fileChangeDao   = db.attach(FileChangeDao.class);
        FileMetricsDao  fileMetricsDao  = db.attach(FileMetricsDao.class);
        FileCouplingDao fileCouplingDao = db.attach(FileCouplingDao.class);

        try {
            // Coupling must be rebuilt from scratch (pre-aggregated)
            fileCouplingDao.deleteAll();

            int commits = new GitWalker(repoDir, commitDao, fileChangeDao, fileCouplingDao).walk();
            int files   = new PmdRunner(repoDir, fileMetricsDao).analyze();

            long duration = System.currentTimeMillis() - start;
            return new IndexResult("ok", files, commits, duration, null);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return new IndexResult("error", 0, 0, duration, e.getMessage());
        }
    }
}
