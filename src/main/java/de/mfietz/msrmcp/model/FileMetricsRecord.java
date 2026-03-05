package de.mfietz.msrmcp.model;

public record FileMetricsRecord(
        String filePath,
        int loc,
        int cyclomaticComplexity,
        int cognitiveComplexity,
        long analyzedAt) {}
