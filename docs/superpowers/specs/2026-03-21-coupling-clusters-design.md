# Design: `get_coupling_clusters` Tool

**Date:** 2026-03-21
**Status:** Approved

## Problem

The existing `get_temporal_coupling` tool returns ranked file pairs. It cannot answer: "which groups of files form a cohesive module based on co-change history?" Cluster detection turns the pairwise coupling data into actionable insight about implicit architectural boundaries.

## Core Insight: Bidirectional Coupling

Existing coupling ratio: `co_changes / MIN(total_a, total_b)` — captures any strong unidirectional coupling.
Hub files (e.g. `pom.xml` changed in 1000 commits) score high against any specific file even when the relationship is one-way.

Cluster-specific ratio: `co_changes / MAX(total_a, total_b)` — requires **both** files to frequently appear together. A hub file with 1000 commits and 8 co-changes with a 10-commit file scores 8/1000 = 0.008, not 0.8. Hub files are naturally excluded without any special filter.

## Two Operating Modes

### Mode 1: Global scan (no `filePath`)

Returns all clusters in the repo, sorted by average internal coupling.

**Algorithm: Connected Components (Union-Find)**

1. Fetch all file pairs from DB where `co_changes / MAX(total_a, total_b) >= minCoupling`
2. Build undirected in-memory graph
3. Find connected components via Union-Find
4. For each component: collect member files + internal edges + compute `avgCoupling`
5. Filter components with fewer than `minClusterSize` files
6. Sort by `avgCoupling` descending, return top `topN`

**Rationale:** Simple, deterministic, no external library. Transitive coupling (A↔B and B↔C implies same domain) is semantically correct. `minCoupling` threshold controls granularity.

### Mode 2: Single-file lookup (`filePath` provided)

Returns the one cluster that contains the given file. Much more efficient: instead of loading the entire coupling graph, a **recursive CTE** in SQLite traverses only the reachable component from the seed file.

```sql
WITH RECURSIVE component(file_id) AS (
  -- seed: the requested file
  SELECT f.file_id FROM files f WHERE f.path = :filePath
  UNION
  -- expand: follow any coupling edge above threshold
  SELECT CASE WHEN fc.file_a_id = c.file_id THEN fc.file_b_id ELSE fc.file_a_id END
  FROM file_coupling fc
  JOIN component c ON fc.file_a_id = c.file_id OR fc.file_b_id = c.file_id
  WHERE CAST(fc.co_changes AS REAL) / MAX(fc.total_changes_a, fc.total_changes_b) >= :minCoupling
)
-- return internal edges of the component
SELECT fa.path AS file_a, fb.path AS file_b,
       fc.co_changes, fc.total_changes_a, fc.total_changes_b,
       CAST(fc.co_changes AS REAL) / MAX(fc.total_changes_a, fc.total_changes_b) AS coupling_ratio
FROM file_coupling fc
JOIN files fa ON fa.file_id = fc.file_a_id
JOIN files fb ON fb.file_id = fc.file_b_id
WHERE fc.file_a_id IN (SELECT file_id FROM component)
  AND fc.file_b_id IN (SELECT file_id FROM component)
```

Result: a single `CouplingClusterResult` (or empty array `[]` if the file has no qualifying partners).

The `sinceEpochMs` variant replaces `file_coupling` with the CTE-based `file_changes` approach (same pattern as `findTopCoupledSince`), with MAX normalization.

No edge cap needed in Mode 2 — the component size is bounded by the repo's actual cluster size.

## Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `filePath` | string | — | If set: single-file lookup (Mode 2). If absent: global scan (Mode 1). |
| `minCoupling` | double | 0.3 | Min threshold for `co_changes / MAX(total_a, total_b)` |
| `minClusterSize` | int | 2 | (Mode 1 only) Filter out clusters smaller than this; JSON schema minimum: 2 |
| `topN` | int | 20 | (Mode 1 only) Max clusters returned, sorted by avg coupling desc; JSON schema minimum: 1 |
| `sinceEpochMs` | long | — | Optional time window (consistent with other coupling tools) |

## Output Format

Same structure for both modes — an array of clusters (Mode 2 returns at most one element):

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
- `edges` — coupling pairs within the cluster with `coChanges` and `couplingRatio` (MAX normalization)
- `avgCoupling` — mean coupling ratio across all internal edges (used for ranking in Mode 1)

## Data Flow

```
filePath == null (Mode 1):
  sinceEpochMs == null:
    FileCouplingDao.findEdgesForClustering(minCoupling)
      → SELECT from file_coupling, MAX normalization, ORDER BY coupling_ratio DESC LIMIT 10000
  sinceEpochMs != null:
    FileCouplingDao.findEdgesForClusteringSince(sinceEpochMs, minCoupling)
      → CTE on file_changes, MAX normalization, ORDER BY coupling_ratio DESC LIMIT 10000
      Note: MAX replaces MIN in BOTH the SELECT expression and the HAVING clause
  → if rows.size() == 10_000: return isError("Edge cap reached …")
  → Union-Find clustering → filter by minClusterSize → sort by avgCoupling → top topN

filePath != null (Mode 2):
  sinceEpochMs == null:
    FileCouplingDao.findClusterForFile(filePath, minCoupling)
      → recursive CTE on file_coupling (see SQL above)
  sinceEpochMs != null:
    FileCouplingDao.findClusterForFileSince(filePath, sinceEpochMs, minCoupling)
      → recursive CTE on file_changes, MAX normalization
  → build single CouplingClusterResult from returned edges (clusterIndex = 1)
  → return [] if no edges returned
```

## Files to Create / Modify

| File | Change |
|---|---|
| `db/FileCouplingDao.java` | Add 4 new queries: `findEdgesForClustering`, `findEdgesForClusteringSince`, `findClusterForFile`, `findClusterForFileSince` — all return `List<CouplingRow>`, all use MAX normalization |
| `tool/GetCouplingClustersTool.java` | New tool: `GetCouplingClustersTool(FileCouplingDao fileCouplingDao, IndexTracker tracker)` — wired in `ToolRegistry.buildSpecs` the same way as `GetSummaryTool`; Union-Find as private static inner class (Mode 1 only) |
| `model/CouplingClusterResult.java` | New file — record `CouplingClusterResult(int clusterIndex, List<String> files, List<ClusterEdge> edges, double avgCoupling)` |
| `model/ClusterEdge.java` | New file — record `ClusterEdge(String fileA, String fileB, int coChanges, double couplingRatio)` |
| `tool/ToolRegistry.java` | Register new tool |
| `tool/ToolSchemas.java` | Add `couplingClusters()` schema |

## Key Implementation Notes

- **IndexTracker guard:** Return `error("Index not ready … Call get_index_status")` when `!tracker.isReady()`, matching the exact message used by all other tools.
- **Empty result guard:** Return empty array `[]` (not error) when no edges meet the threshold.
- **Union-Find:** Standard path-compressed implementation as private static class inside `GetCouplingClustersTool`. Only used in Mode 1.
- **Helper reuse:** Use `import static de.mfietz.msrmcp.tool.GetHotspotsTool.*` — covers `ok()`, `error()`, `intArg()`, `longArg()`, `doubleArg()`, `stringArg()`. Do not define local copies.
- **CouplingRow reuse:** All 4 new DAO queries return `FileCouplingDao.CouplingRow`. SQL must alias the MAX-normalized expression as `coupling_ratio` to satisfy JDBI's `ConstructorMapper`. `totalChangesA` and `totalChangesB` are included for ConstructorMapper compatibility but unused by the clustering algorithm.
- **Edge cap (Mode 1 only):** `ORDER BY coupling_ratio DESC LIMIT 10000`. Check `rows.size() == 10_000` to detect the cap. If hit, return `isError: true` with `"Edge cap reached (10 000 edges). Increase minCoupling to reduce graph size."` — silently returning partial clusters would produce incorrect results. No cap needed in Mode 2.
- **Spotless:** Run `mvn spotless:apply` before committing (AOSP 4-space indent).
