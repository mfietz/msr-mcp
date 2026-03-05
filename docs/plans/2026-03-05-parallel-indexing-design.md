# Design: Parallel diff computation + deferred indexes

**Date:** 2026-03-05

## Problem

Full indexing is sequential: GitWalker computes one commit's diff at a time, and
each flush updates all analytical indexes incrementally. For a 10k-commit repo
on a 4-core machine this wastes most available CPU.

## Decision

Two independent, composable optimisations targeting `runFull()`:

1. **Parallel diff computation** — compute diffs for all commits in a batch
   simultaneously using a thread pool.
2. **Deferred index creation** — drop analytical (query-only) indexes before
   the bulk insert, recreate them in one pass afterwards.

`runIncremental()` is unchanged (few commits → overhead not worth it).

---

## Part 1: Parallel diff computation in GitWalker

### Two-phase batch processing

Each batch of 500 commits is processed in two phases:

**Phase 1 — parallel diff computation**

Submit one `Callable<CommitDiff>` per commit to a fixed thread pool
(`Runtime.getRuntime().availableProcessors()` threads). Each task:
- Gets its own `DiffFormatter` via `ThreadLocal` (JGit objects are not
  thread-safe; `ThreadLocal` ensures one instance per pool thread)
- Calls `getDiffs(repo, commit, df)` and `df.toFileHeader(entry).toEditList()`
- Returns a `CommitDiff(RevCommit commit, List<EntryData> entries)` record
  containing only plain Java data — no JGit objects cross thread boundaries

**Phase 2 — sequential map update**

Futures are retrieved in submission order (= chronological order). Each
`CommitDiff` is processed exactly as today: update `coChanges`, `totalChanges`,
`renames`, build `commitBatch`/`changeBatch`, then `flush()`.

### New records

```java
// inside GitWalker
private record CommitDiff(RevCommit commit, List<EntryData> entries) {}
private record EntryData(String path, String oldPath,
                         boolean isRename, boolean isDelete,
                         int linesAdded, int linesDeleted) {}
```

`EntryData.oldPath` is non-null only for RENAME entries.

### What stays unchanged

- `flush()`, `flushCoupling()`, `mergeRenames()`, `applyRenameInMemory()`
- Flush frequency (every 500 commits)
- Chronological ordering of map updates
- Rename tracking correctness

### Thread pool lifecycle

Created at the start of `walk()`, shut down (not shutdownNow) at the end,
before `flushCoupling()` and `mergeRenames()`.

---

## Part 2: Deferred index creation (Database + Indexer)

### Which indexes are deferrable

Only indexes used for queries, never for insert correctness:

```
idx_commits_author_date
idx_commits_jira_slug
idx_commits_author_email
idx_commits_commitid_authordate
idx_file_changes_commitid
idx_file_changes_fileid_commitid
idx_coupling_a
idx_coupling_b
```

Not deferrable (structural or used during inserts):
- `commits.hash UNIQUE` — `INSERT OR IGNORE` + `findByHashes`
- `files.path UNIQUE` — `INSERT OR IGNORE` + `findByPaths`
- `file_coupling PRIMARY KEY` — `ON CONFLICT DO UPDATE`
- All PRIMARY KEYs

### New methods on Database

```java
public void dropAnalyticalIndexes()    // DROP INDEX IF EXISTS (8 indexes)
public void createAnalyticalIndexes()  // CREATE INDEX IF NOT EXISTS (8 indexes)
```

### runFull() call sequence

```
db.dropAnalyticalIndexes()
GitWalker.walk()               // flush without index maintenance → faster
db.createAnalyticalIndexes()   // single bulk build → faster than incremental
parallel(LocCounter, PmdRunner) // need indexes for their queries
```

---

## Expected speedup (4-core, 10k commits, 500 files)

| Phase | Before | After |
|---|---|---|
| GitWalker diffs | ~8s | ~3s |
| GitWalker flush | ~2s | ~0.5s |
| LocCounter + PmdRunner | ~2s | ~2s |
| **Total** | **~12s** | **~5.5s** |

---

## Testing

Existing acceptance tests cover correctness (same DB state expected regardless
of execution order within a batch). No new tests needed beyond verifying
`mvn test` passes after the refactor.
