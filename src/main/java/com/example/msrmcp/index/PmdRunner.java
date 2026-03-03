package com.example.msrmcp.index;

import com.example.msrmcp.db.FileMetricsDao;
import com.example.msrmcp.model.FileMetricsRecord;
import com.example.msrmcp.pmd.MetricCollectorRule;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.rule.RuleSet;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Runs PMD 7 over Java source files and stores max cyclomatic/cognitive
 * complexity per file in the DB.
 */
final class PmdRunner {

    private static final Logger LOG = Logger.getLogger(PmdRunner.class.getName());

    private final Path repoDir;
    private final FileMetricsDao fileMetricsDao;

    PmdRunner(Path repoDir, FileMetricsDao fileMetricsDao) {
        this.repoDir = repoDir;
        this.fileMetricsDao = fileMetricsDao;
    }

    /** Analyzes all Java files in the repo directory. */
    int analyze() {
        PMDConfiguration config = new PMDConfiguration();
        config.setDefaultLanguageVersion(
                LanguageRegistry.PMD.getLanguageByFullName("Java").getVersion("21"));
        config.addInputPath(repoDir);
        return runPmd(config, null);
    }

    /**
     * Analyzes only the given Java files (incremental use).
     *
     * @param changedPaths repo-relative paths — only {@code .java} files are processed
     */
    int analyze(Set<String> changedPaths) {
        Set<Path> javaFiles = changedPaths.stream()
                .filter(p -> p.endsWith(".java"))
                .map(repoDir::resolve)
                .filter(p -> p.toFile().exists())
                .collect(Collectors.toSet());
        if (javaFiles.isEmpty()) return 0;

        PMDConfiguration config = new PMDConfiguration();
        config.setDefaultLanguageVersion(
                LanguageRegistry.PMD.getLanguageByFullName("Java").getVersion("21"));
        return runPmd(config, javaFiles);
    }

    /**
     * @param specificFiles if non-null, add only these files; otherwise use
     *                      the directory configured in {@code config}
     */
    private int runPmd(PMDConfiguration config, Set<Path> specificFiles) {
        MetricCollectorRule.reset();
        try (PmdAnalysis pmd = PmdAnalysis.create(config)) {
            if (specificFiles != null) {
                specificFiles.forEach(f -> pmd.files().addFile(f));
            }
            pmd.addRuleSet(RuleSet.forSingleRule(new MetricCollectorRule()));
            pmd.performAnalysis();
        } catch (Exception e) {
            LOG.warning("PMD analysis failed: " + e.getMessage());
            return 0;
        }

        // Read from static maps (shared across all PMD-cloned rule instances)
        Map<String, Integer> cyclo     = MetricCollectorRule.getMaxCycloPerFile();
        Map<String, Integer> cognitive = MetricCollectorRule.getMaxCognitivePerFile();
        Map<String, Integer> loc       = MetricCollectorRule.getLocPerFile();

        Set<String> allFiles = new HashSet<>(loc.keySet());
        allFiles.addAll(cyclo.keySet());

        long now = System.currentTimeMillis();
        List<FileMetricsRecord> batch = new ArrayList<>(allFiles.size());
        for (String absPath : allFiles) {
            String relPath = toRelativePath(absPath);
            batch.add(new FileMetricsRecord(
                    relPath,
                    loc.getOrDefault(absPath, 0),
                    cyclo.getOrDefault(absPath, -1),
                    cognitive.getOrDefault(absPath, -1),
                    now));
        }

        if (!batch.isEmpty()) {
            fileMetricsDao.upsertBatch(batch);
        }
        return batch.size();
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
