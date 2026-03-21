package de.mfietz.msrmcp.model;

import java.util.List;

/** A group of files that frequently change together, identified by co-change graph analysis. */
public record CouplingClusterResult(
        int clusterIndex, List<String> files, List<ClusterEdge> edges, double avgCoupling) {}
