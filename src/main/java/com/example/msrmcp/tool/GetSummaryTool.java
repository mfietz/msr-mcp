package com.example.msrmcp.tool;

import com.example.msrmcp.db.CommitDao;
import com.example.msrmcp.db.FileChangeDao;
import com.example.msrmcp.db.FileMetricsDao;
import com.example.msrmcp.model.SummaryResult;
import com.example.msrmcp.model.SummaryResult.TopFile;
import io.modelcontextprotocol.spec.McpSchema.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * MCP tool: {@code get_summary}
 *
 * <p>Returns a quick overview of the indexed repository: commit count,
 * date range, file counts, and the top-5 most-changed files.
 * No arguments required.
 */
public final class GetSummaryTool {

    static final String NAME = "get_summary";
    private static final JsonMapper MAPPER = JsonMapper.shared();
    private static final int TOP_FILES = 5;

    private final CommitDao commitDao;
    private final FileChangeDao fileChangeDao;
    private final FileMetricsDao fileMetricsDao;

    public GetSummaryTool(CommitDao commitDao,
                          FileChangeDao fileChangeDao,
                          FileMetricsDao fileMetricsDao) {
        this.commitDao     = commitDao;
        this.fileChangeDao = fileChangeDao;
        this.fileMetricsDao = fileMetricsDao;
    }

    public CallToolResult handle(Map<String, Object> args) {
        try {
            int totalCommits       = commitDao.count();
            int totalFilesTracked  = fileChangeDao.countDistinctPaths();
            int filesWithMetrics   = fileMetricsDao.count();
            long earliestMs        = commitDao.findEarliestAuthorDate().orElse(0L);
            long latestMs          = commitDao.findLatestAuthorDate().orElse(0L);

            List<TopFile> topFiles = fileChangeDao
                    .findTopChangedFiles(null, "%", "%", TOP_FILES)
                    .stream()
                    .map(r -> new TopFile(r.filePath(), r.changeFrequency()))
                    .toList();

            SummaryResult result = new SummaryResult(
                    totalCommits, totalFilesTracked, filesWithMetrics,
                    earliestMs, latestMs, topFiles);

            return GetHotspotsTool.ok(MAPPER.writeValueAsString(result));
        } catch (Exception e) {
            return GetHotspotsTool.error("get_summary failed: " + e.getMessage());
        }
    }

    static Tool toolSpec() {
        return Tool.builder()
                .name(NAME)
                .description("""
                        Returns an overview of the indexed repository:
                        total commits, file counts, date range, and the top-5 most-changed files.
                        No arguments required.
                        """)
                .inputSchema(ToolSchemas.empty())
                .build();
    }
}
