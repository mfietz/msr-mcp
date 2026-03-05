# Design: get_ownership tool

**Date:** 2026-03-05

## Problem

`get_bus_factor` identifies files where one author dominates, but requires a threshold
(default 0.75) and is designed for risk identification only. There's no way to get a
general ownership overview across all files, or to compute ownership by lines instead
of commits.

`get_file_authors` gives all contributors for a single file, but requires one call per
file — not useful for a repo-wide view.

## Decision

New standalone tool `get_ownership` that returns one row per file with the dominant
author and ownership ratio. Supports both commit-based and line-based ownership.

## Output schema

| Field | Type | Description |
|-------|------|-------------|
| `filePath` | String | Repo-relative path |
| `topAuthorName` | String | Display name of dominant author |
| `topAuthorEmail` | String | Email of dominant author |
| `ownershipRatio` | double | 0.0–1.0: top author's share |
| `topAuthorAmount` | long | Top author's commits or lines (depends on ownershipBy) |
| `totalAmount` | long | Total commits or lines for this file |

## Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `topN` | int | 20 | Max results |
| `ownershipBy` | String | `"commits"` | `"commits"` or `"lines"` |
| `minOwnership` | double | 0.0 | Filter: only files with ownershipRatio >= this |
| `extension` | String | `""` | Extension filter (e.g. `".java"`) |
| `pathFilter` | String | `"%"` | SQL LIKE pattern for path |
| `sinceEpochMs` | Long | null | Only consider commits after this timestamp |

Results sorted by `ownershipRatio DESC`.

## Architecture

### New DAO methods on `CommitDao`

Two separate `@SqlQuery` methods (SQL can't be dynamically parameterized in JDBI):

**`findOwnershipByCommits`** — mirrors `findBusFactorFiles` but without the threshold
WHERE clause, plus extension filter:

```sql
WITH file_author_counts AS (
  SELECT fc.file_id, c.author_email, c.author_name, COUNT(*) AS author_amount
  FROM file_changes fc
  JOIN commits c ON c.commit_id = fc.commit_id
  WHERE (:sinceEpochMs IS NULL OR c.author_date >= :sinceEpochMs)
  GROUP BY fc.file_id, c.author_email
),
file_totals AS (
  SELECT file_id, SUM(author_amount) AS total_amount, MAX(author_amount) AS max_author_amount
  FROM file_author_counts GROUP BY file_id
)
SELECT f.path AS file_path,
       fac.author_email AS top_author_email, fac.author_name AS top_author_name,
       fac.author_amount AS top_author_amount, ft.total_amount,
       CAST(fac.author_amount AS REAL) / ft.total_amount AS ownership_ratio
FROM file_totals ft
JOIN file_author_counts fac ON fac.file_id = ft.file_id
                            AND fac.author_amount = ft.max_author_amount
JOIN files f ON f.file_id = ft.file_id
WHERE f.path LIKE :extensionPattern
  AND f.path LIKE :pathFilter
  AND CAST(fac.author_amount AS REAL) / ft.total_amount >= :minOwnership
ORDER BY ownership_ratio DESC
LIMIT :topN
```

**`findOwnershipByLines`** — same structure, but `SUM(fc.lines_added)` instead of `COUNT(*)`.

### New model record

```java
// CommitDao inner record
record OwnershipRow(String filePath, String topAuthorEmail, String topAuthorName,
                    long topAuthorAmount, long totalAmount, double ownershipRatio) {}
```

Register in `Database.open()`: `ConstructorMapper.factory(CommitDao.OwnershipRow.class)`

### New tool class

`GetOwnershipTool` — follows same pattern as `GetBusFactorTool`:
- Reads `ownershipBy` arg, dispatches to the correct DAO method
- `doubleArg` helper already available via `GetBusFactorTool`

### ToolRegistry + ToolSchemas

Add `get_ownership` spec to `ToolSchemas`, register in `ToolRegistry`.

## Relation to get_bus_factor

`get_bus_factor` remains unchanged — it's purpose-built for risk identification (threshold
filter, dedicated description). `get_ownership` is the general-purpose ownership view.

## Testing

Acceptance test: repo with two files, multiple authors with known commit counts.
Assert `ownershipRatio` is correct for both `ownershipBy=commits` and `ownershipBy=lines`.
