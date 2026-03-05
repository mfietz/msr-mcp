package de.mfietz.msrmcp.model;

public record CommitRecord(
        String hash,
        long authorDate,
        String firstLine,
        String jiraSlug,
        String authorEmail,
        String authorName) {}
