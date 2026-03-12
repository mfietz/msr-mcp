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
│   │                            # Parallel diff: ThreadLocal<DiffFormatter>, two-phase window (submit parallel, retrieve sequential)
│   ├── LocCounter.java          # Language-agnostic LOC counter; skips binaries via null-byte detection
│   │                            # count() for full, count(Set<String>) for incremental
│   └── PmdRunner.java           # PmdAnalysis + MetricCollectorRule; abs→rel path conversion
│                                # Two-phase API: collectMetrics() (scan) + writeBatch() (write); analyze() combines both
│                                # analyze(Set<String>) for incremental (pmd.files().addFile per file)
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
- `@BindList("filePaths")` requires `<filePaths>` angle-bracket syntax in SQL; chunked to 999 (SQLite variable limit)

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
- Constructor must call `setLanguage(LanguageRegistry.PMD.getLanguageByFullName("Java"))`
  (PMD 7 removed the implicit language; omitting it silently skips all files)
- PMD clones rule instances via reflection → use `static ConcurrentHashMap` and call `MetricCollectorRule.reset()` before each `PmdAnalysis` run
- `ServicesResourceTransformer` in shade config is critical for PMD language providers (ServiceLoader)
- PMD metrics API (CYCLO, COGNITIVE) is Java-only; other languages get LOC only

### Multi-language support
- `LocCounter` handles all text files; `PmdRunner` processes only `.java` (others get `cyclomaticComplexity=-1`)
- `HotspotScorer` falls back to normalized LOC when cyclo is -1
- `get_hotspots` default extension is `""` (matches all files, not just `.java`)

### Incremental indexing
- Falls back to `runFull()` when DB is empty
- `GitWalker.walk(stopAtHash)` uses `revWalk.markUninteresting()` to skip already-indexed commits
- Coupling: `upsertBatch` uses `ON CONFLICT DO UPDATE SET co_changes += excluded.co_changes` — no `deleteAll()` needed

### Rename tracking
- Heuristic: per-commit DELETE+ADD pairs with same basename (unambiguous 1:1 only); does NOT use JGit `setDetectRenames`
- `files.path` updated in-place → old `file_id` preserved, history stays continuous
- Limitations: multi-window chains (rename spans two 500-commit windows) and ambiguous basenames are NOT detected

### Background indexing
- `Main` starts indexing in a daemon thread immediately after DB open; MCP server starts without waiting
- Tools guard incomplete data via `tracker.isReady()` — return `error("Index not ready … Call get_index_status")` when false
- Extend the guard to new tools that aggregate across all data

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

**Remaining hotspot (2026-03):** JGit diff computation (`MyersDiff`) on large text files. Binary extension skip, `WS_IGNORE_ALL`, `setContext(0)`, and merge-commit exclusion reduce scope, but Myers diff on large Java files remains the dominant CPU consumer.

## Known gotchas

| Area | Gotcha |
|---|---|
| PMD fat JAR | `ServicesResourceTransformer` in shade config required for PMD language providers |
| Java 25 + sqlite-jdbc | `Enable-Native-Access: ALL-UNNAMED` in MANIFEST required |
| `@BindList` empty list | SQLite `IN ()` is invalid — guard with `if (list.isEmpty()) return …` |
| PMD rule cloning | `static ConcurrentHashMap` + `reset()` before each `PmdAnalysis` run |
| PMD language | `setLanguage()` in `MetricCollectorRule` constructor — omitting silently skips all files |
| Schema migrations | `ALTER TABLE … ADD COLUMN` in try-catch in `Database.open()` — SQLite throws on duplicate column |
| Kotlin complexity | Not possible — PMD 7 Kotlin module has no metrics API; Kotlin gets LOC only |
