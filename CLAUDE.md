# CLAUDE.md — MSR MCP Server

## Package map

```
com.example.msrmcp
├── Main.java                    # Entrypoint: git check → DB → runIncremental → STDIO loop
├── db/
│   ├── Database.java            # Jdbi setup, WAL pragma, DDL, ConstructorMapper registration
│   ├── CommitDao.java           # commits lookup table: insertBatch + findByHashes → CommitIdRecord(commitId, hash)
│   │                            # + findLatestHash + count
│   ├── FileDao.java             # files lookup table: insertBatch + findByPaths → FileRecord(fileId, path)
│   ├── FileChangeDao.java       # insertBatch(FileChangeIdRecord) + query methods JOIN files+commits
│   │                            # + findDistinctPaths (used by LocCounter.count())
│   ├── FileMetricsDao.java      # upsertBatch(FileMetricsIdRecord) + findByPaths JOIN files + count
│   └── FileCouplingDao.java     # upsertBatch(FileCouplingIdRecord, ON CONFLICT accumulate)
│                                # + findTopCoupled/Since/ForFile/ForFileSince JOIN files + deleteAll
├── index/
│   ├── Indexer.java             # runFull(): clear coupling → GitWalker → LocCounter → PmdRunner → deleteStaleMetrics
│   │                            # runIncremental(): walk(latestHash) → targeted Loc+Pmd + gone-path cleanup
│   ├── GitWalker.java           # RevWalk on main/master/HEAD; WalkResult(commitsProcessed,changedPaths)
│   │                            # RevSort.REVERSE → oldest-first walk for correct rename handling
│   │                            # walk(stopAtHash) uses markUninteresting for incremental boundary
│   │                            # EmptyTreeIterator for root commits (no parent)
│   │                            # flush() resolves paths→IDs + hashes→IDs before insert
│   │                            # applyRenameInMemory(): fixes coChanges/totalChanges maps on RENAME diff
│   │                            # mergeRenames(): after walk, merges file_changes old→new file_id, removes old files row
│   ├── LocCounter.java          # Language-agnostic LOC counter; skips binaries via null-byte detection
│   │                            # count() for full, count(Set<String>) for incremental
│   │                            # resolves paths→IDs via FileDao before upsert
│   └── PmdRunner.java           # PmdAnalysis + MetricCollectorRule; abs→rel path conversion
│                                # analyze(Set<String>) for incremental (pmd.files().addFile per file)
│                                # resolves paths→IDs via FileDao before upsert
├── pmd/
│   └── MetricCollectorRule.java # AbstractJavaRule; static ConcurrentHashMap for results
│                                # (PMD clones rule instances — instance maps are empty on clones)
│                                # reset() must be called before each PmdAnalysis run
├── tool/
│   ├── ToolRegistry.java        # buildSpecs() → List<SyncToolSpecification>
│   ├── ToolSchemas.java         # McpSchema.JsonSchema definitions
│   ├── GetHotspotsTool.java     # Also holds shared helpers: ok(), error(), intArg(), longArg(), …
│   ├── GetTemporalCouplingTool.java
│   ├── GetFileCommitHistoryTool.java  # jiraSlug filter via LIKE on commits.jira_slug
│   ├── GetFileAuthorsTool.java        # authors ranked by commit count; uses CommitDao.findAuthorsForFile
│   ├── GetBusFactorTool.java          # dominanceRatio = top author commits / total; CommitDao.findBusFactorFiles
│   ├── GetOwnershipTool.java          # dominant author per file; ownershipBy=commits|lines; CommitDao.findOwnershipByCommits/Lines
│   ├── GetChurnTool.java              # top files by lines added+deleted; FileChangeDao.findTopChurn
│   ├── GetSummaryTool.java            # now also returns uniqueAuthors, topAuthors, languageDistribution
│   └── RefreshIndexTool.java
├── model/                       # Java records: CommitRecord(+authorEmail,authorName), FileChangeRecord,
│                                # FileMetricsRecord, FileCouplingRecord, HotspotResult(+ageInDays,+daysSinceLastChange), IndexResult, SummaryResult
│                                # CommitDao.OwnershipRow (inline record: path, ownerEmail, ownerName, ownerCount, totalCount)
└── util/
    ├── JiraSlugExtractor.java   # regex ^([A-Z]{2,4}-\d+)
    └── HotspotScorer.java       # min-max normalise changeFreq × cyclo (LOC fallback for non-Java)
```

## Build & test

```bash
# compile
mvn compile

# test (acceptance tests — takes ~5 s)
mvn test

# full fat JAR
mvn package -DskipTests

# run against a local repo
cd /some/git/repo
java -jar /path/to/msr-mcp-server.jar
```

## Key patterns

### JDBI + Java records
- Binding records: `@BindMethods` (maps record component accessors by name)
- Reading records: `ConstructorMapper.factory(Foo.class)` registered in `Database.open()`
- `maven.compiler.parameters=true` preserves constructor param names for ConstructorMapper
- `@BindList("filePaths")` requires `<filePaths>` angle-bracket syntax in SQL

### MCP SDK 1.0.0 specifics (differ from 0.x docs)
- Jackson 3: `tools.jackson.databind.json.JsonMapper` — NOT `com.fasterxml`
- `Tool` uses builder: `Tool.builder().name().description().inputSchema(schema).build()`
- `inputSchema` type: `McpSchema.JsonSchema` (record with type/properties/required/…)
- `CallToolResult` uses builder: `CallToolResult.builder().content(list).isError(b).build()`
- Transport: `new StdioServerTransportProvider(McpJsonDefaults.getMapper())`
- Server builder: `.tools(List<SyncToolSpecification>)` registers all tools at once
- `SyncToolSpecification(tool, callHandler)` — second field is `callHandler`
- StdioServerTransportProvider starts non-daemon threads; JVM stays alive until stdin closes.
  Do NOT call `closeGracefully()` immediately after `build()`.

### PMD 7 metric rule
- `MetricCollectorRule extends AbstractJavaRule`
- Constructor must call `setLanguage(LanguageRegistry.PMD.getLanguageByFullName("Java"))`
  (PMD 7 removed the implicit language; omitting it silently skips all files)
- Visits `ASTMethodDeclaration` and `ASTConstructorDeclaration`
- Both implement `ASTExecutableDeclaration`
- `JavaMetrics.CYCLO` / `COGNITIVE_COMPLEXITY` operate on `ASTExecutableDeclaration`
- `MetricsUtil.computeMetric(JavaMetrics.CYCLO, node)` returns `Integer` (nullable)
- PMD clones rule instances via reflection per analysis thread → use `static ConcurrentHashMap`
  and call `MetricCollectorRule.reset()` before each `PmdAnalysis` run
- `RuleSet.forSingleRule(rule)` — confirmed: still clones. Static maps are the fix.
- `ServicesResourceTransformer` in shade config is critical for PMD language providers
- PMD metrics API (CYCLO, COGNITIVE) is Java-only. Other languages get LOC only.

### LocCounter implementation
- Uses `FileChannel` + 64 KB direct `ByteBuffer` for streaming reads (avoids loading entire file into RAM)
- Single pass: null-byte detection (binary skip) and newline counting happen in the same loop
- Formula: `lines = 1 + count('\n')` — trailing newlines count as an extra line (consistent for both LF and CRLF)
- Empty files return 0; binary files throw `IOException` and are silently skipped by the caller

### Multi-language support
- `LocCounter` handles all text files (null-byte → binary → skipped)
- `PmdRunner` processes only `.java` files (others get `cyclomaticComplexity=-1`)
- `HotspotScorer` falls back to normalized LOC when cyclo is -1
- `get_hotspots` default extension is `""` (matches all files, not just `.java`)
- `get_hotspots` now returns `ageInDays` (days since first commit) and `daysSinceLastChange` (days since most recent commit) for each result

### Parallel indexing (LocCounter + PmdRunner)
- After `GitWalker` completes, `LocCounter` and `PmdRunner` run in parallel via a 2-thread `ExecutorService`
- **Write ordering is preserved**: `locFuture.get()` completes first (LocCounter writes LOC for all files), then `pmdRunner.writeBatch(pmdBatch)` overwrites Java files with PMD-derived metrics
- `PmdRunner` exposes two-phase API: `collectMetrics()` / `collectMetrics(Set<String>)` (scan only, no DB write) + `writeBatch(List<FileMetricsIdRecord>)` (write only)
- `analyze()` / `analyze(Set<String>)` remain as convenience wrappers (collect + write)
- Race condition avoided: PMD scan runs concurrently but writes sequentially after LocCounter

### Incremental indexing
- `Indexer.runIncremental()` calls `commitDao.findLatestHash()` to find the boundary
- `GitWalker.walk(stopAtHash)` uses `revWalk.markUninteresting()` to skip already-indexed commits
- `WalkResult(commitsProcessed, changedPaths)` carries the set of touched paths back
- `LocCounter.count(Set<String>)` and `PmdRunner.analyze(Set<String>)` accept specific paths
- Coupling: `upsertBatch` accumulates with `ON CONFLICT DO UPDATE SET co_changes += excluded.co_changes`
  so no `deleteAll()` needed for incremental runs (coupling data accumulates correctly)
- Falls back to `runFull()` when DB is empty

### Path normalization (files lookup table)
- `files(file_id INTEGER PK AUTOINCREMENT, path TEXT UNIQUE)` — central path→ID mapping
- `file_changes`, `file_metrics`, `file_coupling` store integer `file_id` FKs instead of TEXT paths
- DAO query methods JOIN to `files` and return string paths — tool layer unchanged
- DAO insert methods accept ID-based records: `FileChangeIdRecord`, `FileMetricsIdRecord`, `FileCouplingIdRecord`
- Path→ID resolution via `FileDao.insertBatch` (INSERT OR IGNORE) + `findByPaths`

### Commit hash normalization (commits lookup table)
- `commits(commit_id INTEGER PK AUTOINCREMENT, hash TEXT UNIQUE, ...)` — central hash→ID mapping
- `file_changes` stores integer `commit_id` FK instead of TEXT `commit_hash`
- DAO query methods JOIN to `commits` and return string hashes — tool layer unchanged
- Hash→ID resolution via `CommitDao.findByHashes` (chunked to 999)
- `FileChangeIdRecord(long commitId, long fileId)` — both FKs are integers
- Coupling `file_a_id < file_b_id` enforced at flush time (may differ from lexicographic path order)
- `@BindList` chunked to 999 per call (SQLite variable limit)

### Git indexing
- Default branch: `refs/heads/main` → `refs/heads/master` → `HEAD`
- Root commit (no parent): `EmptyTreeIterator` as old-tree side of `DiffFormatter.scan()`
- Walk direction: **oldest-first** (`RevSort.REVERSE`) — required for correct rename handling
- Batch size: 500 commits flushed at once
- co-change map key: `"fileA\0fileB"` (fileA < fileB lexicographic)
- Coupling ratio formula: `co_changes / MIN(total_changes_a, total_changes_b)`

### Rename tracking
- `setDetectRenames(true)` on `DiffFormatter` — JGit detects by content similarity (≥60%)
- On RENAME diff entry: `applyRenameInMemory(oldPath, newPath, coChanges, totalChanges)` fixes in-memory maps so coupling data follows the new name
- After all flushes: `mergeRenames()` applies in chronological order:
  - If old path and new path both have a `file_id`: `UPDATE file_changes SET file_id = newId WHERE file_id = oldId` + `DELETE FROM files WHERE file_id = oldId`
  - If only old path has a `file_id` (no post-rename commits yet): `UPDATE files SET path = newPath WHERE path = oldPath`
- Rename chains (A→B→C) resolve correctly because pairs are processed in commit order

### Deleted file cleanup
- After `LocCounter.count()` in `runFull()`: `deleteStaleMetrics()` removes `file_metrics` rows for paths no longer on disk
- In `runIncremental()`: checks `changedPaths` against disk; deletes metrics for gone paths
- `file_changes` history is retained — only the current-state `file_metrics` is cleaned up

### Temporal coupling `since` routing
- No `sinceEpochMs`: fast path via pre-aggregated `file_coupling` table
- With `sinceEpochMs`: CTE-based self-join on `file_changes` (correct but slower)

## Known risks / fixed bugs

| Risk | Status |
|---|---|
| PMD fat JAR ServiceLoader | Mitigated by `ServicesResourceTransformer` |
| JGit root commit NPE | Fixed: `EmptyTreeIterator` for parent-less commits |
| Java 25 + sqlite-jdbc native access | `Enable-Native-Access: ALL-UNNAMED` in MANIFEST |
| `@BindList` empty list | SQLite `IN ()` is invalid — callers guard with `if (paths.isEmpty()) return …` |
| PMD rule cloning | Fixed: `static ConcurrentHashMap` + `reset()` before each run |
| PMD "Rule has no language" | Fixed: explicit `setLanguage()` in `MetricCollectorRule` constructor |
| Server exits immediately | Fixed: removed `closeGracefully()` call after `build()` |
| Schema migrations (new columns) | `ALTER TABLE commits ADD COLUMN …` in try-catch in `Database.open()` — SQLite throws on duplicate column, we ignore it |
| Kotlin complexity via PMD | Not possible — PMD 7 Kotlin module has no metrics API; Kotlin gets LOC only |
| Rename in same flush batch | Both old+new paths get file_ids in the same batch; `mergeRenames()` post-walk fixes this |
| Rename to existing path (edge case) | File renamed to a path that already exists: `updatePath` silently no-ops (UNIQUE conflict) — accepted limitation |
