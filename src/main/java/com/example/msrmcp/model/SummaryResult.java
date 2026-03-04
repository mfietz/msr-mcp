package com.example.msrmcp.model;

import java.util.List;

/**
 * Result returned by the {@code get_summary} tool.
 *
 * <p>{@code earliestCommitMs} and {@code latestCommitMs} are 0 when the index is empty.
 */
public record SummaryResult(
        int totalCommits,
        int uniqueAuthors,
        int totalFilesTracked,
        int filesWithMetrics,
        long earliestCommitMs,
        long latestCommitMs,
        List<TopFile> topChangedFiles,
        List<AuthorSummary> topAuthors,
        List<LangCount> languageDistribution
) {
    public record TopFile(String filePath, int changeFrequency) {}
    public record AuthorSummary(String name, String email, int commits) {}
    public record LangCount(String extension, int fileCount) {}
}
