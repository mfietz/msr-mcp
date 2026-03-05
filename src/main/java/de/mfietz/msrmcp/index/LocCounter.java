package de.mfietz.msrmcp.index;

import de.mfietz.msrmcp.db.FileChangeDao;
import de.mfietz.msrmcp.db.FileDao;
import de.mfietz.msrmcp.db.FileMetricsDao;
import de.mfietz.msrmcp.db.FileMetricsDao.FileMetricsIdRecord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
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
    private final FileDao fileDao;

    LocCounter(Path repoDir, FileChangeDao fileChangeDao, FileMetricsDao fileMetricsDao, FileDao fileDao) {
        this.repoDir = repoDir;
        this.fileChangeDao = fileChangeDao;
        this.fileMetricsDao = fileMetricsDao;
        this.fileDao = fileDao;
    }

    /** Counts LOC for all git-tracked files. */
    int count() {
        return count(new HashSet<>(fileChangeDao.findDistinctPaths()));
    }

    /** Counts LOC only for the given repo-relative paths (incremental use). */
    int count(Set<String> paths) {
        long now = System.currentTimeMillis();
        // Collect relPath + metrics first, then resolve to IDs
        List<String> countedPaths = new ArrayList<>();
        List<int[]> countedMetrics = new ArrayList<>();

        for (String relPath : paths) {
            Path file = repoDir.resolve(relPath);
            if (!Files.exists(file) || !Files.isRegularFile(file)) continue;
            try {
                int loc = countLines(file);
                countedPaths.add(relPath);
                countedMetrics.add(new int[]{loc});
            } catch (IOException e) {
                // Binary or unreadable file — skip silently
            }
        }

        if (!countedPaths.isEmpty()) {
            Map<String, Long> pathToId = resolvePaths(countedPaths);
            List<FileMetricsIdRecord> batch = new ArrayList<>(countedPaths.size());
            for (int i = 0; i < countedPaths.size(); i++) {
                Long id = pathToId.get(countedPaths.get(i));
                if (id != null) {
                    batch.add(new FileMetricsIdRecord(id, countedMetrics.get(i)[0], -1, -1, now));
                }
            }
            if (!batch.isEmpty()) {
                fileMetricsDao.upsertBatch(batch);
            }
        }
        return countedPaths.size();
    }

    private Map<String, Long> resolvePaths(List<String> paths) {
        int chunkSize = 999;
        for (int i = 0; i < paths.size(); i += chunkSize) {
            fileDao.insertBatch(paths.subList(i, Math.min(i + chunkSize, paths.size())));
        }
        Map<String, Long> result = new HashMap<>();
        for (int i = 0; i < paths.size(); i += chunkSize) {
            for (FileDao.FileRecord r : fileDao.findByPaths(paths.subList(i, Math.min(i + chunkSize, paths.size())))) {
                result.put(r.path(), r.fileId());
            }
        }
        return result;
    }

    private static final int BUFFER_SIZE = 64 * 1024;

    private static int countLines(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (channel.size() == 0) return 0;
            ByteBuffer buf = ByteBuffer.allocateDirect(BUFFER_SIZE);
            int lines = 1;
            while (channel.read(buf) > 0) {
                buf.flip();
                while (buf.hasRemaining()) {
                    byte b = buf.get();
                    if (b == 0) throw new IOException("binary file");
                    if (b == '\n') lines++;
                }
                buf.clear();
            }
            return lines;
        }
    }
}
