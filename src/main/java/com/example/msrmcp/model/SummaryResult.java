package com.example.msrmcp.model;

import java.util.List;

/**
 * Result returned by the {@code get_summary} tool.
 *
 * <p>{@code earliestCommitMs} and {@code latestCommitMs} are 0 when the index is empty.
 */
public record SummaryResult(
        int totalCommits,
        int totalFilesTracked,
        int filesWithMetrics,
        long earliestCommitMs,
        long latestCommitMs,
        List<TopFile> topChangedFiles
) {
    public record TopFile(String filePath, int changeFrequency) {}
}
