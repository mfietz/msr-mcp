package com.example.msrmcp.model;

public record HotspotResult(
        String path,
        int changeFrequency,
        int linesOfCode,
        int cyclomaticComplexity,
        int cognitiveComplexity,
        double hotspotScore,
        int ageInDays,
        int daysSinceLastChange) {}
