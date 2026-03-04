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
│                                # + findTopCoupled/Since/ForFile JOIN files + deleteAll
├── index/
│   ├── Indexer.java             # runFull(): clear coupling → GitWalker → LocCounter → PmdRunner
│   │                            # runIncremental(): walk(latestHash) → targeted Loc+Pmd
│   ├── GitWalker.java           # RevWalk on main/master/HEAD; WalkResult(commitsProcessed,changedPaths)
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
│   ├── ToolRegistry.java        # buildSpecs() → List<SyncToolSpecification>
│   ├── ToolSchemas.java         # McpSchema.JsonSchema definitions
│   ├── GetHotspotsTool.java     # Also holds shared helpers: ok(), error(), intArg(), longArg(), …
│   ├── GetTemporalCouplingTool.java
│   ├── GetFileCommitHistoryTool.java
│   └── RefreshIndexTool.java
├── model/                       # Java records: CommitRecord, FileChangeRecord, FileMetricsRecord,
│                                # FileCouplingRecord, HotspotResult, IndexResult
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

### Multi-language support
- `LocCounter` handles all text files (null-byte → binary → skipped)
- `PmdRunner` processes only `.java` files (others get `cyclomaticComplexity=-1`)
- `HotspotScorer` falls back to normalized LOC when cyclo is -1
- `get_hotspots` default extension is `""` (matches all files, not just `.java`)

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
- Batch size: 500 commits flushed at once
- co-change map key: `"fileA\0fileB"` (fileA < fileB lexicographic)
- Coupling ratio formula: `co_changes / MIN(total_changes_a, total_changes_b)`

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
