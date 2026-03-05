# Design: file_age metric for get_hotspots

**Date:** 2026-03-05

## Problem

File age is most useful in the context of hotspots: a file that has existed
for 10 years and is still the most frequently changed file represents a
different kind of risk than a new file with temporary churn. A standalone
`get_file_age` tool would have few independent use cases beyond this
combination.

## Decision

Extend `get_hotspots` with two new output fields rather than adding a new
tool. No new tool surface, pure YAGNI.

## Output fields added to HotspotResult

| Field | Type | Description |
|-------|------|-------------|
| `ageInDays` | int | Days since first commit: `(now - firstCommitMs) / 86_400_000` |
| `daysSinceLastChange` | int | Days since last commit: `(now - lastCommitMs) / 86_400_000` |

The raw epoch-ms values (`firstCommitMs`, `lastCommitMs`) stay internal —
they are added to `FileChangeFrequencyRow` for computation but not exposed
in JSON output.

## Changes required

1. **`FileChangeDao.FileChangeFrequencyRow`** — add `firstCommitMs` and
   `lastCommitMs` fields.

2. **`FileChangeDao.findTopChangedFiles()`** — add
   `MIN(c.author_date) AS first_commit_ms` and
   `MAX(c.author_date) AS last_commit_ms` to SELECT and GROUP BY.

3. **`HotspotResult`** — add `ageInDays` and `daysSinceLastChange` fields.

4. **`GetHotspotsTool.handle()`** — compute both day values from the row
   timestamps when building `HotspotResult` entries.

## Backward compatibility

JSON consumers that don't know the new fields will ignore them (standard
JSON parsing behaviour). No breaking change.

## Testing

Acceptance test verifies:
- `ageInDays` and `daysSinceLastChange` are present in JSON output
- `ageInDays >= daysSinceLastChange` (file can't be younger than last change)
- Both values are >= 0
