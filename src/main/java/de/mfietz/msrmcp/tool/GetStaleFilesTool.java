package de.mfietz.msrmcp.tool;

import de.mfietz.msrmcp.db.FileChangeDao;
import de.mfietz.msrmcp.db.FileChangeDao.StaleRow;
import de.mfietz.msrmcp.db.FileMetricsDao;
import de.mfietz.msrmcp.model.FileMetricsRecord;
import de.mfietz.msrmcp.model.StaleFileResult;
import io.modelcontextprotocol.spec.McpSchema.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tool: {@code get_stale_files}
 *
 * <p>Returns files that have not been changed for at least N days,
 * ranked by staleness score: norm(daysSinceLastChange) × norm(complexity).
 * Non-Java files use LOC as a complexity proxy.
 *
 * <p>Arguments (all optional):
 * <ul>
 *   <li>{@code minDaysStale} (int, default 180) — minimum days since last change
 *   <li>{@code topN} (int, default 20) — number of results
 *   <li>{@code extension} (String, default "") — file extension filter
 *   <li>{@code pathFilter} (String, default "%") — SQL LIKE path filter
 * </ul>
 */
public final class GetStaleFilesTool {

    static final String NAME = "get_stale_files";
    private static final JsonMapper MAPPER = JsonMapper.shared();

    private final FileChangeDao fileChangeDao;
    private final FileMetricsDao fileMetricsDao;

    public GetStaleFilesTool(FileChangeDao fileChangeDao, FileMetricsDao fileMetricsDao) {
        this.fileChangeDao = fileChangeDao;
        this.fileMetricsDao = fileMetricsDao;
    }

    public CallToolResult handle(Map<String, Object> args) {
        try {
            int topN         = GetHotspotsTool.intArg(args, "topN", 20);
            int minDaysStale = GetHotspotsTool.intArg(args, "minDaysStale", 180);
            String ext       = GetHotspotsTool.stringArg(args, "extension", "");
            String extPattern = "%" + ext;
            String pathFilter = GetHotspotsTool.stringArg(args, "pathFilter", "%");

            long now      = System.currentTimeMillis();
            long cutoffMs = now - (long) minDaysStale * 86_400_000L;

            List<StaleRow> rows =
                    fileChangeDao.findStaleFiles(cutoffMs, extPattern, pathFilter, topN * 3);
            if (rows.isEmpty()) return GetHotspotsTool.ok("[]");

            List<String> paths = rows.stream().map(StaleRow::filePath).toList();
            Map<String, FileMetricsRecord> metricsMap = paths.isEmpty()
                    ? Map.of()
                    : fileMetricsDao.findByPaths(paths).stream()
                            .collect(Collectors.toMap(FileMetricsRecord::filePath, r -> r));

            List<StaleFileResult> scored = score(rows, metricsMap, now);
            List<StaleFileResult> result = scored.subList(0, Math.min(topN, scored.size()));

            return GetHotspotsTool.ok(MAPPER.writeValueAsString(result));
        } catch (Exception e) {
            return GetHotspotsTool.error("get_stale_files failed: " + e.getMessage());
        }
    }

    private static List<StaleFileResult> score(
            List<StaleRow> rows,
            Map<String, FileMetricsRecord> metricsMap,
            long now) {

        // ── Pre-compute raw values ──────────────────────────────────────────
        record Computed(StaleRow row, int daysSince, int ageInDays, int loc, int cyclo) {}
        List<Computed> computed = rows.stream().map(r -> {
            FileMetricsRecord m = metricsMap.get(r.filePath());
            int daysSince = (int) ((now - r.lastCommitMs())  / 86_400_000L);
            int ageInDays = (int) ((now - r.firstCommitMs()) / 86_400_000L);
            int loc   = m != null ? m.loc()                  : 0;
            int cyclo = m != null ? m.cyclomaticComplexity() : -1;
            return new Computed(r, daysSince, ageInDays, loc, cyclo);
        }).toList();

        // ── Normalise daysSinceLastChange ───────────────────────────────────
        int minDays = computed.stream().mapToInt(Computed::daysSince).min().orElse(0);
        int maxDays = computed.stream().mapToInt(Computed::daysSince).max().orElse(1);

        // ── Normalise cyclomatic complexity (skip -1 entries) ───────────────
        List<Integer> validCyclo = computed.stream()
                .map(Computed::cyclo).filter(c -> c > 0).toList();
        int minCyclo = validCyclo.isEmpty() ? 0 : Collections.min(validCyclo);
        int maxCyclo = validCyclo.isEmpty() ? 1 : Collections.max(validCyclo);

        // ── Normalise LOC for fallback ──────────────────────────────────────
        List<Integer> validLoc = computed.stream()
                .map(Computed::loc).filter(l -> l > 0).toList();
        int minLoc = validLoc.isEmpty() ? 0 : Collections.min(validLoc);
        int maxLoc = validLoc.isEmpty() ? 1 : Collections.max(validLoc);

        List<StaleFileResult> results = new ArrayList<>(computed.size());
        for (Computed c : computed) {
            double normDays = normalise(c.daysSince(), minDays, maxDays);
            double normComplexity = c.cyclo() > 0
                    ? normalise(c.cyclo(), minCyclo, maxCyclo)
                    : (c.loc() > 0 ? normalise(c.loc(), minLoc, maxLoc) : 0.0);

            results.add(new StaleFileResult(
                    c.row().filePath(), c.daysSince(), c.ageInDays(),
                    c.loc(), c.cyclo(), normDays * normComplexity));
        }

        results.sort(Comparator.comparingDouble(StaleFileResult::stalenessScore).reversed());
        return results;
    }

    private static double normalise(int value, int min, int max) {
        if (max == min) return 1.0;
        return (double) (value - min) / (max - min);
    }

    static Tool toolSpec() {
        return Tool.builder()
                .name(NAME)
                .description("""
                        Returns files not changed for at least N days, ranked by staleness score
                        (age × complexity). Complex files that haven't been touched in a long time
                        score highest. Useful for identifying legacy risk and refactoring candidates.
                        """)
                .inputSchema(ToolSchemas.staleFiles())
                .build();
    }
}
