package com.example.msrmcp.index;

import com.example.msrmcp.db.FileChangeDao;
import com.example.msrmcp.db.FileMetricsDao;
import com.example.msrmcp.model.FileMetricsRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Counts lines of code for every file tracked in git history that still exists
 * on disk. Language-agnostic — works for any text file. Binary files (those that
 * throw IOException or contain a null byte) are silently skipped.
 *
 * <p>Must run <em>before</em> {@link PmdRunner} so that PmdRunner can overwrite
 * Java entries with the more accurate PMD-derived complexity metrics.
 */
final class LocCounter {

    private static final Logger LOG = Logger.getLogger(LocCounter.class.getName());

    private final Path repoDir;
    private final FileChangeDao fileChangeDao;
    private final FileMetricsDao fileMetricsDao;

    LocCounter(Path repoDir, FileChangeDao fileChangeDao, FileMetricsDao fileMetricsDao) {
        this.repoDir = repoDir;
        this.fileChangeDao = fileChangeDao;
        this.fileMetricsDao = fileMetricsDao;
    }

    /** @return number of files for which LOC was recorded */
    int count() {
        List<String> paths = fileChangeDao.findDistinctPaths();
        long now = System.currentTimeMillis();
        List<FileMetricsRecord> batch = new ArrayList<>();

        for (String relPath : paths) {
            Path file = repoDir.resolve(relPath);
            if (!Files.exists(file) || !Files.isRegularFile(file)) continue;
            try {
                int loc = countLines(file);
                batch.add(new FileMetricsRecord(relPath, loc, -1, -1, now));
            } catch (IOException e) {
                // Binary or unreadable file — skip silently
            }
        }

        if (!batch.isEmpty()) {
            fileMetricsDao.upsertBatch(batch);
        }
        return batch.size();
    }

    private static int countLines(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        // Reject binary files: any null byte is a strong indicator
        for (byte b : bytes) {
            if (b == 0) throw new IOException("binary file");
        }
        if (bytes.length == 0) return 0;
        int lines = 1;
        for (byte b : bytes) {
            if (b == '\n') lines++;
        }
        return lines;
    }
}
