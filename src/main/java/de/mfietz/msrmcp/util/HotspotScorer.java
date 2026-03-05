package de.mfietz.msrmcp.util;

import de.mfietz.msrmcp.db.FileChangeDao.FileChangeFrequencyRow;
import de.mfietz.msrmcp.model.FileMetricsRecord;
import de.mfietz.msrmcp.model.HotspotResult;
import java.util.*;

/**
 * Scores files as hotspots by combining change frequency and code complexity.
 *
 * <p>Algorithm:
 *
 * <ol>
 *   <li>Min-max normalise {@code changeFrequency} across all candidates → [0,1]
 *   <li>Min-max normalise {@code cyclomaticComplexity} (skip files where it is -1)
 *   <li>For files where cyclomatic is -1 (PMD failed), use normalised LOC instead
 *   <li>{@code score = normFreq × normComplexity}
 * </ol>
 */
public final class HotspotScorer {

    private HotspotScorer() {}

    /**
     * @param candidates files and their change frequencies
     * @param metricsMap keyed by filePath; may be incomplete
     * @return scored and sorted list (highest score first)
     */
    public static List<HotspotResult> score(
            List<FileChangeFrequencyRow> candidates, Map<String, FileMetricsRecord> metricsMap) {

        if (candidates.isEmpty()) return List.of();
        long now = System.currentTimeMillis();

        // ── Normalise change frequency ──────────────────────────────────────
        int minFreq =
                candidates.stream()
                        .mapToInt(FileChangeFrequencyRow::changeFrequency)
                        .min()
                        .orElse(0);
        int maxFreq =
                candidates.stream()
                        .mapToInt(FileChangeFrequencyRow::changeFrequency)
                        .max()
                        .orElse(1);

        // ── Collect valid cyclomatic values for normalisation ───────────────
        List<Integer> validCyclo =
                candidates.stream()
                        .map(r -> metricsMap.getOrDefault(r.filePath(), null))
                        .filter(m -> m != null && m.cyclomaticComplexity() > 0)
                        .map(FileMetricsRecord::cyclomaticComplexity)
                        .toList();

        int minCyclo = validCyclo.isEmpty() ? 0 : Collections.min(validCyclo);
        int maxCyclo = validCyclo.isEmpty() ? 1 : Collections.max(validCyclo);

        // ── Collect LOC values for fallback normalisation ───────────────────
        List<Integer> validLoc =
                candidates.stream()
                        .map(r -> metricsMap.getOrDefault(r.filePath(), null))
                        .filter(Objects::nonNull)
                        .map(FileMetricsRecord::loc)
                        .filter(l -> l > 0)
                        .toList();
        int minLoc = validLoc.isEmpty() ? 0 : Collections.min(validLoc);
        int maxLoc = validLoc.isEmpty() ? 1 : Collections.max(validLoc);

        List<HotspotResult> results = new ArrayList<>(candidates.size());
        for (FileChangeFrequencyRow row : candidates) {
            FileMetricsRecord m = metricsMap.get(row.filePath());
            int loc = m != null ? m.loc() : 0;
            int cyclo = m != null ? m.cyclomaticComplexity() : -1;
            int cogni = m != null ? m.cognitiveComplexity() : -1;

            double normFreq = normalise(row.changeFrequency(), minFreq, maxFreq);

            double normComplexity;
            if (cyclo > 0) {
                normComplexity = normalise(cyclo, minCyclo, maxCyclo);
            } else {
                // Fallback: use LOC as complexity proxy
                normComplexity = loc > 0 ? normalise(loc, minLoc, maxLoc) : 0.0;
            }

            double score = normFreq * normComplexity;
            int ageInDays = (int) ((now - row.firstCommitMs()) / 86_400_000L);
            int daysSinceLastChange = (int) ((now - row.lastCommitMs()) / 86_400_000L);
            results.add(
                    new HotspotResult(
                            row.filePath(),
                            row.changeFrequency(),
                            loc,
                            cyclo,
                            cogni,
                            score,
                            ageInDays,
                            daysSinceLastChange));
        }

        results.sort(Comparator.comparingDouble(HotspotResult::hotspotScore).reversed());
        return results;
    }

    private static double normalise(int value, int min, int max) {
        if (max == min) return 1.0;
        return (double) (value - min) / (max - min);
    }
}
