package com.example.msrmcp.model;

/** Invariant: fileA < fileB (lexicographic) to avoid duplicate pairs. */
public record FileCouplingRecord(
        String fileA,
        String fileB,
        int coChanges,
        int totalChangesA,
        int totalChangesB) {}
