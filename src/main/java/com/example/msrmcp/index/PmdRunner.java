package com.example.msrmcp.index;

import com.example.msrmcp.db.FileMetricsDao;
import com.example.msrmcp.model.FileMetricsRecord;
import com.example.msrmcp.pmd.MetricCollectorRule;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.LanguageVersion;
import net.sourceforge.pmd.lang.rule.RuleSet;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

/**
 * Runs PMD 7 over all Java source files in the repo and stores
 * max cyclomatic/cognitive complexity per file in the DB.
 */
final class PmdRunner {

    private static final Logger LOG = Logger.getLogger(PmdRunner.class.getName());

    private final Path repoDir;
    private final FileMetricsDao fileMetricsDao;

    PmdRunner(Path repoDir, FileMetricsDao fileMetricsDao) {
        this.repoDir = repoDir;
        this.fileMetricsDao = fileMetricsDao;
    }

    /** @return number of source files analyzed */
    int analyze() {
        PMDConfiguration config = new PMDConfiguration();
        // Target Java 21 source level (broad compatibility, PMD 7 supports it well)
        LanguageVersion javaVersion = LanguageRegistry.PMD
                .getLanguageByFullName("Java")
                .getVersion("21");
        config.setDefaultLanguageVersion(javaVersion);
        config.addInputPath(repoDir);

        // Reset static collector before analysis. PMD clones rule instances via
        // reflection for each thread, so results must be stored in static maps.
        MetricCollectorRule.reset();
        try (PmdAnalysis pmd = PmdAnalysis.create(config)) {
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
            // Convert absolute path → repo-relative for consistency with GitWalker paths
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
            String rel = absPath.substring(base.length());
            // Normalise separator and remove leading slash/backslash
            rel = rel.replace('\\', '/');
            while (rel.startsWith("/")) rel = rel.substring(1);
            return rel;
        }
        return absPath;
    }
}
