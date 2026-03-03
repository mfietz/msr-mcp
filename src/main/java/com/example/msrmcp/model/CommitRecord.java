package com.example.msrmcp.model;

public record CommitRecord(String hash, long authorDate, String firstLine, String jiraSlug) {}
