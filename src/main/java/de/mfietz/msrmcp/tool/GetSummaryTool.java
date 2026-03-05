package de.mfietz.msrmcp.tool;

import de.mfietz.msrmcp.db.CommitDao;
import de.mfietz.msrmcp.db.FileChangeDao;
import de.mfietz.msrmcp.db.FileDao;
import de.mfietz.msrmcp.db.FileMetricsDao;
import de.mfietz.msrmcp.model.SummaryResult;
import de.mfietz.msrmcp.model.SummaryResult.AuthorSummary;
import de.mfietz.msrmcp.model.SummaryResult.LangCount;
import de.mfietz.msrmcp.model.SummaryResult.TopFile;
import io.modelcontextprotocol.spec.McpSchema.*;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP tool: {@code get_summary}
 *
 * <p>Returns a quick overview of the indexed repository: commit count, date range, file counts,
 * top-5 most-changed files, top authors, and language distribution. No arguments required.
 */
public final class GetSummaryTool {

    static final String NAME = "get_summary";
    private static final JsonMapper MAPPER = JsonMapper.shared();
    private static final int TOP_FILES = 5;
    private static final int TOP_AUTHORS = 5;

    private final CommitDao commitDao;
    private final FileChangeDao fileChangeDao;
    private final FileMetricsDao fileMetricsDao;
    private final FileDao fileDao;

    public GetSummaryTool(
            CommitDao commitDao,
            FileChangeDao fileChangeDao,
            FileMetricsDao fileMetricsDao,
            FileDao fileDao) {
        this.commitDao = commitDao;
        this.fileChangeDao = fileChangeDao;
        this.fileMetricsDao = fileMetricsDao;
        this.fileDao = fileDao;
    }

    public CallToolResult handle(Map<String, Object> args) {
        try {
            int totalCommits = commitDao.count();
            int uniqueAuthors = commitDao.countDistinctAuthors();
            int totalFilesTracked = fileChangeDao.countDistinctPaths();
            int filesWithMetrics = fileMetricsDao.count();
            long earliestMs = commitDao.findEarliestAuthorDate().orElse(0L);
            long latestMs = commitDao.findLatestAuthorDate().orElse(0L);

            List<TopFile> topFiles =
                    fileChangeDao.findTopChangedFiles(null, "%", "%", TOP_FILES).stream()
                            .map(r -> new TopFile(r.filePath(), r.changeFrequency()))
                            .toList();

            List<AuthorSummary> topAuthors =
                    commitDao.findTopAuthors(TOP_AUTHORS).stream()
                            .map(
                                    r ->
                                            new AuthorSummary(
                                                    r.authorName(),
                                                    r.authorEmail(),
                                                    r.commitCount()))
                            .toList();

            List<LangCount> languageDistribution = buildLangDistribution();

            SummaryResult result =
                    new SummaryResult(
                            totalCommits,
                            uniqueAuthors,
                            totalFilesTracked,
                            filesWithMetrics,
                            earliestMs,
                            latestMs,
                            topFiles,
                            topAuthors,
                            languageDistribution);

            return GetHotspotsTool.ok(MAPPER.writeValueAsString(result));
        } catch (Exception e) {
            return GetHotspotsTool.error("get_summary failed: " + e.getMessage());
        }
    }

    private List<LangCount> buildLangDistribution() {
        Map<String, Integer> counts = new TreeMap<>();
        for (String path : fileDao.findAllPaths()) {
            int dot = path.lastIndexOf('.');
            String ext = dot >= 0 ? path.substring(dot) : "(no ext)";
            counts.merge(ext, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(
                        Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                                .thenComparing(Map.Entry.comparingByKey()))
                .map(e -> new LangCount(e.getKey(), e.getValue()))
                .toList();
    }

    static Tool toolSpec() {
        return Tool.builder()
                .name(NAME)
                .description(
                        """
                        Returns an overview of the indexed repository:
                        total commits, unique authors, file counts, date range,
                        top-5 most-changed files, top-5 authors by commit count,
                        and file count by language/extension.
                        No arguments required.
                        """)
                .inputSchema(ToolSchemas.empty())
                .build();
    }
}
