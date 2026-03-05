# Parallel Indexing Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Speed up `runFull()` indexing by (1) deferring analytical index maintenance until after all bulk inserts complete, and (2) computing per-commit diffs in parallel across a thread pool while preserving chronological sequential map-update semantics.

**Architecture:** Two independent, composable changes. `Database.java` gains two public methods for dropping/recreating 8 analytical indexes. `Indexer.runFull()` wraps `GitWalker.walk()` with those calls. `GitWalker.walk()` is refactored from a flat sequential loop into a two-phase window processor: Phase 1 submits diff tasks to a fixed-thread pool (one `DiffFormatter` per thread via `ThreadLocal`), Phase 2 retrieves futures in submission order and updates in-memory maps sequentially.

**Tech Stack:** Java 21+ records, `java.util.concurrent` (ExecutorService, Future, ThreadLocal), JGit `DiffFormatter`, JDBI SQLite.

---

### Task 1: Deferred indexes — Database.java

**Files:**
- Modify: `src/main/java/com/example/msrmcp/db/Database.java`

The 8 analytical indexes are created at startup via the schema-application loop. They are never consulted during `GitWalker` inserts — only when tools query the DB. Dropping them before the bulk walk and rebuilding them once after saves incremental B-tree maintenance on every `INSERT`.

**Which indexes are safe to defer** (query-only, no role in insert correctness):
- `idx_commits_author_date`, `idx_commits_jira_slug`, `idx_commits_author_email`, `idx_commits_commitid_authordate`
- `idx_file_changes_commitid`, `idx_file_changes_fileid_commitid`
- `idx_coupling_a`, `idx_coupling_b`

**Which are NOT deferrable** (structural):
- `commits.hash UNIQUE` — enforced by `INSERT OR IGNORE` and relied on by `findByHashes`
- `files.path UNIQUE` — enforced by `INSERT OR IGNORE` and relied on by `findByPaths`
- `file_coupling PRIMARY KEY (file_a_id, file_b_id)` — enforced by `ON CONFLICT DO UPDATE`
- All `PRIMARY KEY` autoincrement columns

**Step 1: Add two new public methods to `Database`**

After the `attach()` method (line ~124), add:

```java
/**
 * Drops the 8 analytical (query-only) indexes to speed up bulk inserts.
 * Call before a full re-index; restore with {@link #createAnalyticalIndexes()} after.
 * Safe to call multiple times (uses IF EXISTS).
 */
public void dropAnalyticalIndexes() {
    jdbi.useHandle(h -> {
        for (String name : List.of(
                "idx_commits_author_date",
                "idx_commits_jira_slug",
                "idx_commits_author_email",
                "idx_commits_commitid_authordate",
                "idx_file_changes_commitid",
                "idx_file_changes_fileid_commitid",
                "idx_coupling_a",
                "idx_coupling_b")) {
            h.execute("DROP INDEX IF EXISTS " + name);
        }
    });
}

/**
 * Recreates the 8 analytical indexes dropped by {@link #dropAnalyticalIndexes()}.
 * Uses IF NOT EXISTS so it is safe to call even if indexes were never dropped.
 */
public void createAnalyticalIndexes() {
    jdbi.useHandle(h -> {
        for (String sql : List.of(
                "CREATE INDEX IF NOT EXISTS idx_commits_author_date ON commits(author_date)",
                "CREATE INDEX IF NOT EXISTS idx_commits_jira_slug ON commits(jira_slug) WHERE jira_slug IS NOT NULL",
                "CREATE INDEX IF NOT EXISTS idx_commits_author_email ON commits(author_email)",
                "CREATE INDEX IF NOT EXISTS idx_commits_commitid_authordate ON commits(commit_id, author_date)",
                "CREATE INDEX IF NOT EXISTS idx_file_changes_commitid ON file_changes(commit_id)",
                "CREATE INDEX IF NOT EXISTS idx_file_changes_fileid_commitid ON file_changes(file_id, commit_id)",
                "CREATE INDEX IF NOT EXISTS idx_coupling_a ON file_coupling(file_a_id)",
                "CREATE INDEX IF NOT EXISTS idx_coupling_b ON file_coupling(file_b_id)")) {
            h.execute(sql);
        }
    });
}
```

No new imports needed — `java.util.List` is already imported.

**Step 2: Compile**

```bash
mvn compile 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

---

### Task 2: Wire deferred indexes into Indexer.runFull()

**Files:**
- Modify: `src/main/java/com/example/msrmcp/index/Indexer.java`

**Step 1: Update `runFull()`**

In `runFull()`, replace this block:

```java
fileCouplingDao.deleteAll();

System.err.println("MSR:   walking git history...");
GitWalker.WalkResult walk = new GitWalker(repoDir, commitDao, fileChangeDao, fileCouplingDao, fileDao).walk();
System.err.printf("MSR:   %,d commits done. running LOC + PMD in parallel...%n", walk.commitsProcessed());
```

With:

```java
fileCouplingDao.deleteAll();
db.dropAnalyticalIndexes();

System.err.println("MSR:   walking git history...");
GitWalker.WalkResult walk = new GitWalker(repoDir, commitDao, fileChangeDao, fileCouplingDao, fileDao).walk();
System.err.printf("MSR:   %,d commits done. rebuilding indexes...%n", walk.commitsProcessed());
db.createAnalyticalIndexes();
System.err.println("MSR:   running LOC + PMD in parallel...");
```

`runIncremental()` is intentionally not touched.

**Step 2: Run test suite**

```bash
mvn test 2>&1 | tail -15
```

Expected: all tests GREEN.

---

### Task 3: Parallel diff computation — GitWalker.java

**Files:**
- Modify: `src/main/java/com/example/msrmcp/index/GitWalker.java`

**Step 1: Add new imports**

After the existing `import java.util.logging.Logger;` line, add:

```java
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
```

**Step 2: Add two private records**

After the existing `private record ChangeEntry(String hash, String path, int linesAdded, int linesDeleted) {}` line, add:

```java
private record CommitDiff(RevCommit commit, List<EntryData> entries) {}

private record EntryData(String path, String oldPath, boolean isRename,
                         int linesAdded, int linesDeleted) {}
```

`oldPath` is non-null only when `isRename == true`. `EntryData` contains only plain Java data — no JGit objects cross thread boundaries.

**Step 3: Add static `computeCommitDiff` method**

Add after `applyRenameInMemory()`:

```java
/**
 * Computes the diff for a single commit and returns serialisable data only.
 * Safe to call from any thread because the caller supplies its own {@code df}.
 * Exceptions inside individual entry processing are swallowed (best-effort).
 */
private static CommitDiff computeCommitDiff(Repository repo, RevCommit commit,
                                             DiffFormatter df) {
    List<EntryData> entries = new ArrayList<>();
    try {
        for (DiffEntry entry : getDiffs(repo, commit, df)) {
            boolean isDelete = entry.getChangeType() == DiffEntry.ChangeType.DELETE;
            boolean isRename = entry.getChangeType() == DiffEntry.ChangeType.RENAME;
            String path    = isDelete ? entry.getOldPath() : entry.getNewPath();
            String oldPath = isRename ? entry.getOldPath() : null;
            int linesAdded = 0, linesDeleted = 0;
            try {
                for (Edit edit : df.toFileHeader(entry).toEditList()) {
                    linesAdded   += edit.getEndB() - edit.getBeginB();
                    linesDeleted += edit.getEndA() - edit.getBeginA();
                }
            } catch (Exception ignored) {}
            entries.add(new EntryData(path, oldPath, isRename, linesAdded, linesDeleted));
        }
    } catch (Exception ignored) {}
    return new CommitDiff(commit, entries);
}
```

**Step 4: Add `processWindow` instance method**

Add directly after `computeCommitDiff`:

```java
/**
 * Two-phase batch processor.
 *
 * <p>Phase 1: submits one diff-computation task per commit to {@code pool};
 * each task uses a thread-local {@code DiffFormatter} (JGit objects are not
 * thread-safe).
 *
 * <p>Phase 2: retrieves futures in submission order (= chronological order)
 * and updates all in-memory maps sequentially — same semantics as the old loop.
 */
private void processWindow(
        List<RevCommit> window, Repository repo,
        ThreadLocal<DiffFormatter> dfLocal, ExecutorService pool,
        List<CommitRecord> commitBatch, List<ChangeEntry> changeBatch,
        Map<String, int[]> coChanges, Map<String, int[]> totalChanges,
        List<String[]> renames, Set<String> allChangedPaths) {

    // Phase 1 — parallel diff computation
    List<Future<CommitDiff>> futures = new ArrayList<>(window.size());
    for (RevCommit commit : window) {
        futures.add(pool.submit(() -> computeCommitDiff(repo, commit, dfLocal.get())));
    }

    // Phase 2 — sequential map update in chronological order
    for (Future<CommitDiff> future : futures) {
        CommitDiff cd;
        try {
            cd = future.get();
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        RevCommit commit = cd.commit();
        String hash        = commit.getName();
        long authorDate    = commit.getAuthorIdent().getWhen().getTime();
        String firstLine   = commit.getShortMessage();
        String jiraSlug    = JiraSlugExtractor.extract(firstLine);
        String authorEmail = commit.getAuthorIdent().getEmailAddress();
        String authorName  = commit.getAuthorIdent().getName();

        commitBatch.add(new CommitRecord(hash, authorDate, firstLine, jiraSlug, authorEmail, authorName));

        List<String> changedPaths = new ArrayList<>(cd.entries().size());
        for (EntryData e : cd.entries()) {
            changeBatch.add(new ChangeEntry(hash, e.path(), e.linesAdded(), e.linesDeleted()));
            changedPaths.add(e.path());
            totalChanges.computeIfAbsent(e.path(), k -> new int[1])[0]++;
            allChangedPaths.add(e.path());
            if (e.isRename()) {
                renames.add(new String[]{e.oldPath(), e.path()});
                applyRenameInMemory(e.oldPath(), e.path(), coChanges, totalChanges);
            }
        }

        if (changedPaths.size() <= MAX_PATHS_FOR_COUPLING) {
            accumulateCoChanges(changedPaths, coChanges);
        }
    }

    flush(commitBatch, changeBatch);
}
```

**Step 5: Replace the main loop in `walk(String stopAtHash)`**

Remove these sections from `walk()`:
- The `DiffFormatter df = new DiffFormatter(...)` block (lines ~95–98)
- Everything from `List<CommitRecord> commitBatch` through `if (!commitBatch.isEmpty()) flush(...)` and `df.close()` (lines ~105–157)

Replace with:

```java
int nThreads = Runtime.getRuntime().availableProcessors();
ExecutorService pool = Executors.newFixedThreadPool(nThreads);
List<DiffFormatter> formatters = Collections.synchronizedList(new ArrayList<>());
ThreadLocal<DiffFormatter> dfLocal = ThreadLocal.withInitial(() -> {
    DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
    df.setRepository(repo);
    df.setDiffComparator(RawTextComparator.DEFAULT);
    df.setDetectRenames(true);
    formatters.add(df);
    return df;
});

Map<String, int[]> coChanges    = new HashMap<>();
Map<String, int[]> totalChanges = new HashMap<>();
Set<String> allChangedPaths     = new HashSet<>();
List<String[]> renames          = new ArrayList<>();

List<CommitRecord> commitBatch = new ArrayList<>(BATCH_SIZE);
List<ChangeEntry>  changeBatch  = new ArrayList<>(BATCH_SIZE * 4);
int processed = 0;

List<RevCommit> window = new ArrayList<>(BATCH_SIZE);
for (RevCommit commit : revWalk) {
    window.add(commit);
    if (window.size() == BATCH_SIZE) {
        processWindow(window, repo, dfLocal, pool, commitBatch, changeBatch,
                      coChanges, totalChanges, renames, allChangedPaths);
        processed += window.size();
        window.clear();
        System.err.printf("MSR:   %,d commits processed...%n", processed);
    }
}
if (!window.isEmpty()) {
    processWindow(window, repo, dfLocal, pool, commitBatch, changeBatch,
                  coChanges, totalChanges, renames, allChangedPaths);
    processed += window.size();
}

pool.shutdown();
formatters.forEach(DiffFormatter::close);
flushCoupling(coChanges, totalChanges);
mergeRenames(renames);

return new WalkResult(processed, allChangedPaths);
```

The `flushCoupling()` and `mergeRenames()` calls remain identical. `df.close()` is replaced by closing all per-thread formatters from the `formatters` list after `pool.shutdown()`.

**Key correctness points:**
- `RevCommit` fields (`getName()`, `getAuthorIdent()`, etc.) are read-only after parsing — safe from any thread
- Each thread has its own `DiffFormatter` via `ThreadLocal` — no shared mutable JGit state
- `formatters` list is `Collections.synchronizedList` because multiple pool threads may call `ThreadLocal.withInitial` simultaneously
- All futures are `get()`'d before `pool.shutdown()` — shutdown completes immediately
- Rename tracking and co-change semantics unchanged (Phase 2 is sequential in chronological order)

**Step 6: Compile**

```bash
mvn compile 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`.

**Step 7: Run full test suite**

```bash
mvn test 2>&1 | tail -20
```

Expected: all tests GREEN. The rename-tracking tests (`RenameTrackingAcceptanceTest`, `RenameChainAcceptanceTest`, `RenameCouplingAcceptanceTest`, `IncrementalRenameAcceptanceTest`) are the most sensitive correctness validators for this change.

---

### Task 4: CLAUDE.md + commit + push

**Files:**
- Modify: `CLAUDE.md`

**Step 1: Add two new subsections to CLAUDE.md**

In the "Key patterns" section, after the "Deleted file cleanup" subsection, add:

```
### Deferred index creation (runFull only)
- `db.dropAnalyticalIndexes()` called before `GitWalker.walk()` in `runFull()`
- `db.createAnalyticalIndexes()` called once after `walk()` completes, before LocCounter/PmdRunner
- 8 query-only indexes on `commits`, `file_changes`, `file_coupling` are deferred
- Structural indexes (UNIQUE on hash/path, PKs, coupling PK) are never dropped
- `runIncremental()` is unchanged

### Parallel diff computation (GitWalker)
- Fixed thread pool: `Runtime.getRuntime().availableProcessors()` threads
- `ThreadLocal<DiffFormatter>` — one JGit formatter per pool thread (JGit not thread-safe)
- Two-phase window processing per 500-commit batch:
  - Phase 1: submit `computeCommitDiff` tasks in parallel
  - Phase 2: retrieve futures in chronological order, update maps sequentially
- `CommitDiff` / `EntryData` private records carry only plain Java data across threads
- All `DiffFormatter` instances tracked in a synchronized list, closed after `pool.shutdown()`
- Rename tracking and co-change semantics unchanged (Phase 2 is sequential)
```

**Step 2: Compile + test one final time**

```bash
mvn test 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`, all tests GREEN.

**Step 3: Commit**

```bash
git add \
  src/main/java/com/example/msrmcp/db/Database.java \
  src/main/java/com/example/msrmcp/index/Indexer.java \
  src/main/java/com/example/msrmcp/index/GitWalker.java \
  CLAUDE.md
git commit -m "$(cat <<'EOF'
perf: parallel diff computation + deferred indexes for runFull()

Two composable optimisations targeting full re-index:
- Database: drop 8 analytical indexes before bulk insert, rebuild once after
- GitWalker: compute diffs in parallel (ThreadLocal DiffFormatter per pool
  thread), update in-memory maps sequentially in chronological order

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

**Step 4: Push**

```bash
git push
```
