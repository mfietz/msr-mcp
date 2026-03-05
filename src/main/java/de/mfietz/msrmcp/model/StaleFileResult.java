package de.mfietz.msrmcp.model;

public record StaleFileResult(
        String filePath,
        int daysSinceLastChange,
        int ageInDays,
        int loc,
        int cyclomaticComplexity,
        double stalenessScore) {}
