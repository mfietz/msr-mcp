# Design: get_stale_files tool

## Goal

Add a `get_stale_files` MCP tool that identifies files which haven't been touched in a long time and are ranked by a staleness score combining age with complexity. Helps AI agents find "cold but complex" code — refactoring candidates and legacy risk.

## Concept

Stale code = files not changed in N days. Risky stale code = stale + complex. The score combines both dimensions via the same min-max normalization used by `get_hotspots`.

**Score formula:** `norm(daysSinceLastChange) × norm(cyclomaticComplexity)` — LOC fallback for non-Java files (same as HotspotScorer).

## Tool Interface

**Name:** `get_stale_files`

**Parameters:**

| Parameter | Type | Default | Description |
|---|---|---|---|
| `minDaysStale` | int | 180 | Only include files not changed for at least N days |
| `topN` | int | 20 | Max results returned |
| `extension` | String | `""` | File extension filter (e.g. `".java"`) |
| `pathFilter` | String | `"%"` | SQL LIKE path filter |

**Output per file:**
```json
{
  "filePath": "src/main/java/Foo.java",
  "daysSinceLastChange": 312,
  "ageInDays": 1240,
  "loc": 450,
  "cyclomaticComplexity": 18,
  "stalenessScore": 0.84
}
```

## Architecture

No schema changes required — all data already exists in `file_changes`, `commits`, and `file_metrics`.

### New files
- `model/StaleFileResult.java` — result record
- `tool/GetStaleFilesTool.java` — tool handler
- New method `FileChangeDao.findStaleFiles(Long minDaysMs, String extPattern, String pathFilter, int limit)`
- New schema in `ToolSchemas.staleFiles()`
- Registration in `ToolRegistry`

### Data flow

```
FileChangeDao.findStaleFiles(minDaysMs, extPattern, pathFilter, topN×3)
  SQL: GROUP BY file_id
       HAVING MAX(author_date) <= :cutoffMs
  Returns: filePath, lastCommitMs, firstCommitMs

FileMetricsDao.findByPaths(paths)
  Returns: loc, cyclomaticComplexity

Java (inline scoring):
  daysSinceLastChange = (now - lastCommitMs) / 86400000
  ageInDays = (now - firstCommitMs) / 86400000
  score = norm(daysSinceLastChange) × norm(complexity | loc)
  sort DESC, take topN
```

`topN×3` fetched from SQL to leave headroom for re-ranking (same pattern as `get_hotspots`).

### SQL query sketch

```sql
SELECT f.path AS file_path,
       MAX(c.author_date) AS last_commit_ms,
       MIN(c.author_date) AS first_commit_ms
FROM file_changes fc
JOIN files f   ON f.file_id   = fc.file_id
JOIN commits c ON c.commit_id = fc.commit_id
WHERE f.path LIKE :extensionPattern
  AND f.path LIKE :pathFilter
GROUP BY fc.file_id
HAVING MAX(c.author_date) <= :cutoffMs
ORDER BY last_commit_ms ASC
LIMIT :limit
```

## Testing

New `GetStaleFilesAcceptanceTest` covering:
1. File not touched in N days appears in results
2. File changed recently is excluded when `minDaysStale` filter applies
3. `minDaysStale` boundary is respected exactly
4. Score ranking: complex stale file ranks above simple stale file
