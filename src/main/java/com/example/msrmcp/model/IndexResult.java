package com.example.msrmcp.model;

public record IndexResult(
        String status,
        int filesIndexed,
        int commitsProcessed,
        long durationMs,
        String errorMessage) {}
