package de.mfietz.msrmcp.model;

/** One coupling edge between two files within a co-change cluster. */
public record ClusterEdge(String fileA, String fileB, int coChanges, double couplingRatio) {}
