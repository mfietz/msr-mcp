# Design: `get_coupling_clusters` Tool

**Date:** 2026-03-21
**Status:** Approved

## Problem

The existing `get_temporal_coupling` tool returns ranked file pairs. It cannot answer: "which groups of files form a cohesive module based on co-change history?" Cluster detection turns the pairwise coupling data into actionable insight about implicit architectural boundaries.

## Core Insight: Bidirectional Coupling

Existing coupling ratio: `co_changes / MIN(total_a, total_b)` — captures any strong unidirectional coupling.
Hub files (e.g. `pom.xml` changed in 1000 commits) score high against any specific file even when the relationship is one-way.

Cluster-specific ratio: `co_changes / MAX(total_a, total_b)` — requires **both** files to frequently appear together. A hub file with 1000 commits and 8 co-changes with a 10-commit file scores 8/1000 = 0.008, not 0.8. Hub files are naturally excluded without any special filter.

## Algorithm: Connected Components (Union-Find)

1. Fetch all file pairs from DB where `co_changes / MAX(total_a, total_b) >= minCoupling`
2. Build undirected in-memory graph
3. Find connected components via Union-Find
4. For each component: collect member files + internal edges + compute `avgCoupling`
5. Filter components with fewer than `minClusterSize` files
6. Sort by `avgCoupling` descending, return top `topN`

**Rationale for connected components:** Simple, deterministic, no external library. Transitive coupling (A↔B and B↔C implies same domain) is semantically correct. `minCoupling` threshold controls granularity — raise it to get tighter, smaller clusters.

## Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `minCoupling` | double | 0.3 | Min threshold for `co_changes / MAX(total_a, total_b)` |
| `minClusterSize` | int | 2 | Filter out clusters smaller than this |
| `topN` | int | 20 | Max clusters returned, sorted by avg coupling desc |
| `sinceEpochMs` | long | — | Optional time window (consistent with other coupling tools) |

## Output Format

```json
[
  {
    "clusterIndex": 1,
    "files": ["src/auth/LoginService.java", "src/auth/AuthFilter.java", "src/auth/TokenStore.java"],
    "edges": [
      { "fileA": "src/auth/LoginService.java", "fileB": "src/auth/AuthFilter.java",
        "coChanges": 18, "couplingRatio": 0.82 },
      { "fileA": "src/auth/AuthFilter.java", "fileB": "src/auth/TokenStore.java",
        "coChanges": 14, "couplingRatio": 0.71 }
    ],
    "avgCoupling": 0.76
  }
]
```

Each cluster includes:
- `clusterIndex` — 1-based ranking position
- `files` — all files in the cluster (sorted)
- `edges` — coupling pairs within the cluster with `coChanges` and `couplingRatio` (using MAX normalization)
- `avgCoupling` — mean coupling ratio across all internal edges (used for ranking)

## Data Flow

```
sinceEpochMs == null:
  FileCouplingDao.findEdgesForClustering(minCoupling)
    → SELECT from file_coupling using MAX normalization, no LIMIT (internal cap: 10 000 edges)

sinceEpochMs != null:
  FileCouplingDao.findEdgesForClusteringSince(sinceEpochMs, minCoupling)
    → CTE on file_changes (same pattern as findTopCoupledSince), MAX normalization

Both paths feed into:
  GetCouplingClustersTool.handle()
    → Union-Find clustering → filter → sort → serialize
```

## Files to Create / Modify

| File | Change |
|---|---|
| `db/FileCouplingDao.java` | Add `findEdgesForClustering(minCoupling)` and `findEdgesForClusteringSince(sinceEpochMs, minCoupling)` — both return `List<CouplingRow>`, using MAX normalization |
| `tool/GetCouplingClustersTool.java` | New tool class with Union-Find as private static inner class |
| `model/CouplingClusterResult.java` | New file — records `CouplingClusterResult(int clusterIndex, List<String> files, List<ClusterEdge> edges, double avgCoupling)` and `ClusterEdge(String fileA, String fileB, int coChanges, double couplingRatio)` |
| `tool/ToolRegistry.java` | Register new tool |
| `tool/ToolSchemas.java` | Add `couplingClusters()` schema |

## Key Implementation Notes

- **IndexTracker guard:** Return `error("Index not ready")` when `!tracker.isReady()`, consistent with other tools.
- **Empty graph guard:** Return empty array `[]` (not error) when no edges meet the threshold.
- **Union-Find:** Standard path-compressed implementation as private static class inside `GetCouplingClustersTool`. No external library.
- **CouplingRow reuse:** Both new DAO queries return the existing `FileCouplingDao.CouplingRow` — `couplingRatio` field will carry the MAX-normalized value.
- **Edge cap:** Fetch at most 10 000 edges from DB (via SQL `LIMIT 10000`) to prevent memory pressure on large repos. Log a warning if the cap is hit.
- **Spotless:** Run `mvn spotless:apply` before committing (AOSP 4-space indent).
