package de.mfietz.msrmcp.index;

import de.mfietz.msrmcp.db.FileDao;
import de.mfietz.msrmcp.db.FileMetricsDao;
import de.mfietz.msrmcp.db.FileMetricsDao.FileMetricsIdRecord;
import de.mfietz.msrmcp.pmd.MetricCollectorRule;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.rule.RuleSet;

/**
 * Runs PMD 7 over Java source files and stores max cyclomatic/cognitive complexity per file in the
 * DB.
 */
final class PmdRunner {

    private static final Logger LOG = Logger.getLogger(PmdRunner.class.getName());

    private final Path repoDir;
    private final FileMetricsDao fileMetricsDao;
    private final FileDao fileDao;

    PmdRunner(Path repoDir, FileMetricsDao fileMetricsDao, FileDao fileDao) {
        this.repoDir = repoDir;
        this.fileMetricsDao = fileMetricsDao;
        this.fileDao = fileDao;
    }

    /** Analyzes all Java files in the repo directory (scan + write). */
    int analyze() {
        List<FileMetricsIdRecord> batch = collectMetrics();
        writeBatch(batch);
        return batch.size();
    }

    /**
     * Analyzes only the given Java files (incremental use, scan + write).
     *
     * @param changedPaths repo-relative paths — only {@code .java} files are processed
     */
    int analyze(Set<String> changedPaths) {
        List<FileMetricsIdRecord> batch = collectMetrics(changedPaths);
        writeBatch(batch);
        return batch.size();
    }

    /**
     * Scans all Java files in the repo directory and returns metrics without writing to the
     * database. Call {@link #writeBatch} separately to persist.
     */
    List<FileMetricsIdRecord> collectMetrics() {
        PMDConfiguration config = new PMDConfiguration();
        config.setDefaultLanguageVersion(
                LanguageRegistry.PMD.getLanguageByFullName("Java").getVersion("21"));
        config.addInputPath(repoDir);
        return scanPmd(config, null);
    }

    /**
     * Scans only the given Java files and returns metrics without writing to the database. Call
     * {@link #writeBatch} separately to persist.
     *
     * @param changedPaths repo-relative paths — only {@code .java} files are processed
     */
    List<FileMetricsIdRecord> collectMetrics(Set<String> changedPaths) {
        Set<Path> javaFiles =
                changedPaths.stream()
                        .filter(p -> p.endsWith(".java"))
                        .map(repoDir::resolve)
                        .filter(p -> p.toFile().exists())
                        .collect(Collectors.toSet());
        if (javaFiles.isEmpty()) return List.of();

        PMDConfiguration config = new PMDConfiguration();
        config.setDefaultLanguageVersion(
                LanguageRegistry.PMD.getLanguageByFullName("Java").getVersion("21"));
        return scanPmd(config, javaFiles);
    }

    /** Persists a batch of previously collected metrics to the database. */
    void writeBatch(List<FileMetricsIdRecord> batch) {
        if (!batch.isEmpty()) {
            fileMetricsDao.upsertBatch(batch);
        }
    }

    /**
     * Runs PMD and returns collected metrics without writing to the database.
     *
     * @param specificFiles if non-null, add only these files; otherwise use the directory
     *     configured in {@code config}
     */
    private List<FileMetricsIdRecord> scanPmd(PMDConfiguration config, Set<Path> specificFiles) {
        MetricCollectorRule.reset();
        try (PmdAnalysis pmd = PmdAnalysis.create(config)) {
            if (specificFiles != null) {
                specificFiles.forEach(f -> pmd.files().addFile(f));
            }
            pmd.addRuleSet(RuleSet.forSingleRule(new MetricCollectorRule()));
            pmd.performAnalysis();
        } catch (Exception e) {
            LOG.warning("PMD analysis failed: " + e.getMessage());
            return List.of();
        }

        // Read from static maps (shared across all PMD-cloned rule instances)
        Map<String, Integer> cyclo = MetricCollectorRule.getMaxCycloPerFile();
        Map<String, Integer> cognitive = MetricCollectorRule.getMaxCognitivePerFile();
        Map<String, Integer> loc = MetricCollectorRule.getLocPerFile();

        Set<String> allFiles = new HashSet<>(loc.keySet());
        allFiles.addAll(cyclo.keySet());

        long now = System.currentTimeMillis();
        List<String> relPaths = new ArrayList<>(allFiles.size());
        List<int[]> metricsList = new ArrayList<>(allFiles.size());
        for (String absPath : allFiles) {
            String relPath = toRelativePath(absPath);
            relPaths.add(relPath);
            metricsList.add(
                    new int[] {
                        loc.getOrDefault(absPath, 0),
                        cyclo.getOrDefault(absPath, -1),
                        cognitive.getOrDefault(absPath, -1)
                    });
        }

        if (relPaths.isEmpty()) return List.of();

        Map<String, Long> pathToId = resolvePaths(relPaths);
        List<FileMetricsIdRecord> batch = new ArrayList<>(relPaths.size());
        for (int i = 0; i < relPaths.size(); i++) {
            Long id = pathToId.get(relPaths.get(i));
            if (id != null) {
                int[] m = metricsList.get(i);
                batch.add(new FileMetricsIdRecord(id, m[0], m[1], m[2], now));
            }
        }
        return batch;
    }

    private Map<String, Long> resolvePaths(List<String> paths) {
        int chunkSize = 999;
        for (int i = 0; i < paths.size(); i += chunkSize) {
            fileDao.insertBatch(paths.subList(i, Math.min(i + chunkSize, paths.size())));
        }
        Map<String, Long> result = new HashMap<>();
        for (int i = 0; i < paths.size(); i += chunkSize) {
            for (FileDao.FileRecord r :
                    fileDao.findByPaths(paths.subList(i, Math.min(i + chunkSize, paths.size())))) {
                result.put(r.path(), r.fileId());
            }
        }
        return result;
    }

    private String toRelativePath(String absPath) {
        String base = repoDir.toAbsolutePath().toString();
        if (absPath.startsWith(base)) {
            String rel = absPath.substring(base.length()).replace('\\', '/');
            while (rel.startsWith("/")) rel = rel.substring(1);
            return rel;
        }
        return absPath;
    }
}
