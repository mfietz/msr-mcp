package de.mfietz.msrmcp.tool;

import de.mfietz.msrmcp.db.FileChangeDao;
import de.mfietz.msrmcp.db.FileChangeDao.FileChangeFrequencyRow;
import de.mfietz.msrmcp.db.FileMetricsDao;
import de.mfietz.msrmcp.model.FileMetricsRecord;
import de.mfietz.msrmcp.model.HotspotResult;
import de.mfietz.msrmcp.util.HotspotScorer;
import io.modelcontextprotocol.spec.McpSchema.*;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP tool: {@code get_hotspots}
 *
 * <p>Arguments (all optional):
 *
 * <ul>
 *   <li>{@code topN} (int, default 20) — number of results
 *   <li>{@code sinceEpochMs} (long) — filter commits after this timestamp
 *   <li>{@code extension} (String, default ".java") — file extension filter
 * </ul>
 */
public final class GetHotspotsTool {

    static final String NAME = "get_hotspots";
    private static final JsonMapper MAPPER = JsonMapper.shared();

    private final FileChangeDao fileChangeDao;
    private final FileMetricsDao fileMetricsDao;

    public GetHotspotsTool(FileChangeDao fileChangeDao, FileMetricsDao fileMetricsDao) {
        this.fileChangeDao = fileChangeDao;
        this.fileMetricsDao = fileMetricsDao;
    }

    public CallToolResult handle(Map<String, Object> args) {
        try {
            int topN = intArg(args, "topN", 20);
            Long sinceEpochMs = longArg(args, "sinceEpochMs");
            String ext = stringArg(args, "extension", "");
            String extPattern = "%" + ext; // "" → "%" matches all files
            String pathFilter = stringArg(args, "pathFilter", "%");

            List<FileChangeFrequencyRow> rows =
                    fileChangeDao.findTopChangedFiles(sinceEpochMs, extPattern, pathFilter, topN);

            List<String> paths = rows.stream().map(FileChangeFrequencyRow::filePath).toList();
            Map<String, FileMetricsRecord> metricsMap =
                    paths.isEmpty()
                            ? Map.of()
                            : fileMetricsDao.findByPaths(paths).stream()
                                    .collect(
                                            Collectors.toMap(
                                                    FileMetricsRecord::filePath,
                                                    Function.identity()));

            List<HotspotResult> scored = HotspotScorer.score(rows, metricsMap);
            List<HotspotResult> result = scored.subList(0, Math.min(topN, scored.size()));

            return ok(MAPPER.writeValueAsString(result));
        } catch (Exception e) {
            return error("get_hotspots failed: " + e.getMessage());
        }
    }

    static Tool toolSpec() {
        return Tool.builder()
                .name(NAME)
                .description(
                        """
                        Returns the top-N hotspot files ranked by change frequency × complexity.
                        Optionally filter by time window (sinceEpochMs) and file extension.
                        """)
                .inputSchema(ToolSchemas.hotspots())
                .build();
    }

    // ── Shared helpers used by all tool classes ──────────────────────────────

    static CallToolResult ok(String json) {
        return CallToolResult.builder().content(List.of(new TextContent(json))).build();
    }

    static CallToolResult error(String msg) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(msg)))
                .isError(true)
                .build();
    }

    static int intArg(Map<String, Object> args, String key, int def) {
        Object v = args.get(key);
        if (v == null) return def;
        return ((Number) v).intValue();
    }

    static Long longArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return null;
        return ((Number) v).longValue();
    }

    static String stringArg(Map<String, Object> args, String key, String def) {
        Object v = args.get(key);
        return v == null ? def : v.toString();
    }

    static double doubleArg(Map<String, Object> args, String key, double def) {
        Object v = args.get(key);
        if (v == null) return def;
        return ((Number) v).doubleValue();
    }
}
