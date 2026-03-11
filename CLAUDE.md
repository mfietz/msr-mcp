# CLAUDE.md — MSR MCP Server

## Package map

```
de.mfietz.msrmcp
├── Main.java                    # Entrypoint: git check → DB → background runIncremental → STDIO loop
├── db/
│   ├── Database.java            # Jdbi setup, WAL pragma, DDL, ConstructorMapper registration
│   ├── CommitDao.java           # commits lookup table: insertBatch + findByHashes → CommitIdRecord(commitId, hash)
│   │                            # + findLatestHash + count
│   ├── FileDao.java             # files lookup table: insertBatch + findByPaths → FileRecord(fileId, path)
│   ├── FileChangeDao.java       # insertBatch(FileChangeIdRecord) + query methods JOIN files+commits
│   │                            # + findDistinctPaths + findStaleFiles + findTopChurn + findTopChangedFiles
│   ├── FileMetricsDao.java      # upsertBatch(FileMetricsIdRecord) + findByPaths JOIN files + count
│   └── FileCouplingDao.java     # upsertBatch(FileCouplingIdRecord, ON CONFLICT accumulate)
│                                # + findTopCoupled/Since/ForFile/ForFileSince JOIN files + deleteAll
├── index/
│   ├── Indexer.java             # runFull(): clear coupling → GitWalker → LocCounter → PmdRunner → deleteStaleMetrics
│   │                            # runIncremental(): walk(latestHash) → targeted Loc+Pmd + gone-path cleanup
│   ├── IndexTracker.java        # Thread-safe state machine: NOT_STARTED→INDEXING→READY|ERROR
│   │                            # markIndexing/markReady(elapsedMs)/markError(msg); isReady() guards tools
│   ├── MailMap.java             # Parses .mailmap from repo root; resolve(name,email)→canonical Identity
│   │                            # Supports all 4 git formats; case-insensitive email match; first match wins
│   │                            # Missing file → no-op (returns input unchanged)
│   ├── GitWalker.java           # RevWalk on main/master/HEAD; WalkResult(commitsProcessed,changedPaths)
│   │                            # RevSort.REVERSE → oldest-first walk (chronological order for co-change semantics)
│   │                            # walk(stopAtHash) uses markUninteresting for incremental boundary
│   │                            # EmptyTreeIterator for root commits (no parent)
│   │                            # flush() resolves paths→IDs + hashes→IDs before insert
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
│   ├── ToolRegistry.java        # buildSpecs(…, IndexTracker) → List<SyncToolSpecification>
│   ├── ToolSchemas.java         # McpSchema.JsonSchema definitions
│   ├── GetHotspotsTool.java     # Also holds shared helpers: ok(), error(), intArg(), longArg(), …
│   ├── GetIndexStatusTool.java  # returns IndexTracker state as JSON; never returns isError
│   │                            # { status, startedAtMs, elapsedMs?, errorMessage? }
│   ├── GetTemporalCouplingTool.java
│   ├── GetFileCommitHistoryTool.java  # jiraSlug filter via LIKE on commits.jira_slug
│   ├── GetFileAuthorsTool.java        # authors ranked by commit count; uses CommitDao.findAuthorsForFile
│   ├── GetBusFactorTool.java          # dominanceRatio = top author commits / total; CommitDao.findBusFactorFiles
│   ├── GetOwnershipTool.java          # dominant author per file; ownershipBy=commits|lines; CommitDao.findOwnershipByCommits/Lines
│   ├── GetChurnTool.java              # top files by lines added+deleted; FileChangeDao.findTopChurn
│   ├── GetSummaryTool.java            # returns uniqueAuthors, topAuthors, languageDistribution
│   │                                  # guards: returns isError if IndexTracker is not READY
│   ├── GetStaleFilesTool.java         # files not changed in N days × complexity score; FileChangeDao.findStaleFiles
│   └── RefreshIndexTool.java
├── model/                       # Java records: CommitRecord(+authorEmail,authorName), FileChangeRecord,
│                                # FileMetricsRecord, FileCouplingRecord, HotspotResult(+ageInDays,+daysSinceLastChange), IndexResult, SummaryResult,
│                                # StaleFileResult(filePath, daysSinceLastChange, ageInDays, loc, cyclomaticComplexity, stalenessScore)
│                                # CommitDao.OwnershipRow (inline record: path, ownerEmail, ownerName, ownerCount, totalCount)
└── util/
    ├── JiraSlugExtractor.java   # regex ^([A-Z]{2,4}-\d+)
    └── HotspotScorer.java       # min-max normalise changeFreq × cyclo (LOC fallback for non-Java)
```

## Requirements

- **Java 25** (`maven.compiler.release=25`) — no wrapper; use [SDKMAN](https://sdkman.io/): `sdk install java 25-open`
- **Maven 3.9+** — no wrapper; use SDKMAN: `sdk install maven`

## Build & test

```bash
# compile
mvn compile

# test (acceptance tests — takes ~5 s)
mvn test

# full fat JAR
mvn package -DskipTests

# check for outdated dependencies / plugins
mvn versions:display-dependency-updates
mvn versions:display-plugin-updates

# run against a local repo
cd /some/git/repo
java -jar /path/to/msr-mcp-server.jar
```

## CI/CD

Two workflows in `.github/workflows/`:

| Workflow | Trigger | What it does |
|---|---|---|
| `ci.yml` | Every push / PR | `mvn spotless:check` → `mvn verify` (compile + test + package) |
| `release.yml` | Push `v*` tag or manual dispatch | `mvn package -DskipTests` → GitHub Release with fat JAR |

**Local equivalents of CI gates:**

```bash
mvn spotless:check          # mirrors "Check formatting" step
mvn verify -q               # mirrors "Build and test" step (compile + test + package)
```

Run both before pushing to avoid CI failures. `mvn spotless:apply` fixes formatting in-place if `spotless:check` fails.

**Releasing:** push a `vX.Y.Z` tag or trigger `release.yml` manually with the tag name. The fat JAR (`target/msr-mcp-server.jar`) is attached to the GitHub Release automatically.

## Code style

Formatter: **google-java-format 1.28.0 (AOSP style — 4-space indent)** via Spotless.

```bash
mvn spotless:apply   # format all Java files in-place
mvn spotless:check   # verify formatting (exits non-zero if dirty)
```

Conventions observed in this codebase:
- `final` classes everywhere (no subclassing)
- Java records for all data/result types
- SQL in text blocks (`"""..."""`)
- Package-private visibility by default; `public` only at API boundaries
- Shared tool helpers (`ok()`, `error()`, `intArg()`, …) live in `GetHotspotsTool` — reuse, don't duplicate
- Guard `@BindList` calls: `if (list.isEmpty()) return …` before calling any DAO method with `IN (...)`

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

### .mailmap author deduplication
- `MailMap.load(repoDir)` is called once at the start of each `walk()` — loaded before the RevWalk opens
- If `.mailmap` does not exist the result is an empty `MailMap`; `resolve()` returns input unchanged
- Applied in `processWindow()` before building `CommitRecord`: raw `PersonIdent` → `MailMap.resolve()` → canonical name/email
- Four supported formats (per git-check-mailmap(1)):
  - `Proper Name <commit@email>` — override name when email matches
  - `<proper@email> <commit@email>` — override email when email matches
  - `Proper Name <proper@email> <commit@email>` — override both when email matches
  - `Proper Name <proper@email> Commit Name <commit@email>` — override both when name AND email match
- Email matching is case-insensitive; first matching entry wins
- Result: all analytics tools (`get_file_authors`, `get_summary`, `get_ownership`, etc.) see canonical identities

### Git indexing
- Default branch: `refs/heads/main` → `refs/heads/master` → `HEAD`
- Root commit (no parent): `EmptyTreeIterator` as old-tree side of `DiffFormatter.scan()`
- Walk direction: **oldest-first** (`RevSort.REVERSE`) — required for correct co-change semantics (pairs must be recorded in chronological order)
- Batch size: 500 commits flushed at once
- co-change map key: `"fileA\0fileB"` (fileA < fileB lexicographic)
- Coupling ratio formula: `co_changes / MIN(total_changes_a, total_changes_b)`

### Rename tracking
- Heuristic detection: per-commit DELETE+ADD pairs sharing the same basename (unambiguous 1:1 only)
- When detected: `files.path` is updated in-place via `FileDao.updatePath` — old `file_id` is preserved
- `changeBatch` entries are rewritten in `flush()` so `resolvePaths` never re-inserts the old path
- Renames are accumulated into `windowRenames` across the whole 500-commit window and applied once via
  `applyRenamesInMemory()` — O(coChanges) per window instead of per-commit
- `resolveRename()` follows multi-hop chains within the same window (A→B in commit 100, B→C in commit 200
  within same window) using a simple loop with a depth cap
- `totalChanges` key swap uses `merge()` to handle collisions when the canonical path already has an entry
  from commits after the rename
- Multi-window chains (rename spans two 500-commit windows) and ambiguous basenames (two files same name)
  are NOT detected
- Does NOT use JGit `setDetectRenames` — our heuristic handles the common case without content loading

### Deleted file cleanup
- After `LocCounter.count()` in `runFull()`: `deleteStaleMetrics()` removes `file_metrics` rows for paths no longer on disk
- In `runIncremental()`: checks `changedPaths` against disk; deletes metrics for gone paths
- `file_changes` history is retained — only the current-state `file_metrics` is cleaned up

### Deferred index creation (runFull only)
- `db.dropAnalyticalIndexes()` called before `GitWalker.walk()` in `runFull()`
- `db.createAnalyticalIndexes()` called once after `walk()` completes, before LocCounter/PmdRunner
- 8 query-only indexes on `commits`, `file_changes`, `file_coupling` are deferred
- Structural indexes (UNIQUE on hash/path, PKs, coupling PK) are never dropped
- `runIncremental()` is unchanged

### Parallel diff computation (GitWalker)
- Fixed thread pool: `Runtime.getRuntime().availableProcessors()` threads
- `ThreadLocal<DiffFormatter>` — one JGit formatter per pool thread (JGit not thread-safe)
- DiffFormatter configured with `RawTextComparator.WS_IGNORE_ALL` (whitespace runs as single tokens —
  faster diff on indented code; whitespace-only edits count as 0 lines changed) and `setContext(0)`
  (no context lines needed for line-count aggregation — smaller edit lists, less memory)
- Binary file extension check before `toEditList()` — images, fonts, archives, compiled files, media,
  documents are skipped without loading blob content or running Myers diff
- Merge commits (`parentCount > 1`): `toEditList()` skipped entirely (line counts stay 0) and excluded
  from co-change accumulation — merges are integration events, not real work
- Two-phase window processing per 500-commit batch:
  - Phase 1: submit `computeCommitDiff` tasks in parallel
  - Phase 2: retrieve futures in chronological order, update maps sequentially
- `CommitDiff` / `EntryData` private records carry only plain Java data across threads
- Pool + formatters wrapped in `try/finally` for cleanup on exception path
- Co-change map updates are sequential (Phase 2) to preserve chronological order
- `probeRepoSize()`: sums pack file sizes via `File.length()` only (no I/O into pack content); estimates
  file count via `packBytes / 500` heuristic — used to pre-size `coChanges`/`totalChanges`/`allChangedPaths` maps and tune cache
- `applyWindowCache()`: sizes JGit's pack-file window cache between 128 MB (small repos) and 512 MB
  based on actual pack size — called once before the walk

### Background indexing and IndexTracker
- `Main` starts indexing in a daemon thread (`msr-indexer`) immediately after DB open
- MCP server starts without waiting for indexing to complete
- `IndexTracker` is passed to `ToolRegistry.buildSpecs()` and forwarded to tools that guard against
  incomplete data (currently `GetSummaryTool`; extend to other tools as needed)
- State transitions: `NOT_STARTED → INDEXING → READY | ERROR`
- `markIndexing()` records `startedAtMs`; `markReady(elapsedMs)` / `markError(msg)` called from the
  indexer thread once `runIncremental()` returns
- `get_index_status` tool exposes the tracker state as JSON — callers should poll this before
  relying on analytics results:
  `{ "status": "ready", "startedAtMs": 1741723200000, "elapsedMs": 4231 }`
- Tools that guard: check `tracker.isReady()` at the top of `handle()`; return
  `GetHotspotsTool.error("Index not ready (status: indexing). Call get_index_status …")` when false

### Temporal coupling `since` routing
- No `sinceEpochMs`: fast path via pre-aggregated `file_coupling` table
- With `sinceEpochMs`: CTE-based self-join on `file_changes` (correct but slower)

## Performance profiling (JFR)

The server auto-indexes on startup and exits when stdin closes — so `< /dev/null` gives a clean one-shot run:

```bash
cd /some/git/repo
java -XX:StartFlightRecording=filename=/tmp/msr.jfr,settings=profile \
     -jar /path/to/msr-mcp-server.jar < /dev/null 2>&1
```

Dump while running (useful for long indexes):
```bash
jcmd <PID> JFR.dump name=msr filename=/tmp/msr-now.jfr
```

Analyze:
```bash
jfr summary /tmp/msr.jfr          # event counts — GC volume is the first signal
jfr print --events jdk.ObjectAllocationSample /tmp/msr.jfr | grep objectClass | sort | uniq -c | sort -rn | head -20

# top CPU hotspot methods (top frame of each execution sample):
jfr print --events jdk.ExecutionSample /tmp/msr.jfr | python3 -c "
import sys, re
from collections import Counter
frames=[]; in_stack=False; current=[]
for line in sys.stdin:
    line=line.rstrip()
    if 'stackTrace = [' in line: in_stack=True; current=[]
    elif in_stack and line.strip()==']': in_stack=False; frames.append(current[0]) if current else None
    elif in_stack and 'line:' in line:
        m=re.search(r'(\S+\.\S+)\(', line)
        if m: current.append(m.group(1))
c=Counter(frames)
[print(f'{n:6d}  {m}') for m,n in c.most_common(30)]
"
```

DB state mid-run: `sqlite3 /path/to/.msr/msr.db "SELECT COUNT(*) FROM commits; SELECT COUNT(*) FROM file_changes;"`

**Remaining hotspot (2026-03):** JGit diff computation (`MyersDiff`) on large text files — inherent cost of line-level diffing. Binary extension skip, `WS_IGNORE_ALL`, `setContext(0)`, and merge-commit exclusion reduce the scope significantly, but Myers diff on large Java files remains the dominant CPU consumer.

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
| Rename tracking | Supported via basename-heuristic: unambiguous 1:1 DELETE+ADD pairs per commit update `files.path` in-place |
