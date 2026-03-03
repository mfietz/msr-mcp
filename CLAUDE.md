# CLAUDE.md — MSR MCP Server

## Package map

```
com.example.msrmcp
├── Main.java                    # Entrypoint: git check → DB → index → STDIO loop
├── db/
│   ├── Database.java            # Jdbi setup, WAL pragma, DDL, ConstructorMapper registration
│   ├── CommitDao.java           # INSERT OR IGNORE + findLatestHash + findByHash
│   ├── FileChangeDao.java       # insertBatch + findTopChangedFiles + findCommitHashesForFile
│   ├── FileMetricsDao.java      # upsertBatch + findByPaths (@BindList)
│   └── FileCouplingDao.java     # upsertBatch (ON CONFLICT) + findTopCoupled + findTopCoupledSince
├── index/
│   ├── Indexer.java             # runFull(): deleteAll coupling → GitWalker → PmdRunner
│   ├── GitWalker.java           # RevWalk on main/master/HEAD; EmptyTreeIterator for root commits
│   └── PmdRunner.java           # PmdAnalysis + MetricCollectorRule; abs→rel path conversion
├── pmd/
│   └── MetricCollectorRule.java # AbstractJavaRule; CYCLO+COGNITIVE via MetricsUtil on ASTExecutableDeclaration
├── tool/
│   ├── ToolRegistry.java        # buildSpecs() → List<SyncToolSpecification>
│   ├── ToolSchemas.java         # McpSchema.JsonSchema definitions
│   ├── GetHotspotsTool.java     # Also holds shared helpers: ok(), error(), intArg(), …
│   ├── GetTemporalCouplingTool.java
│   ├── GetFileCommitHistoryTool.java
│   └── RefreshIndexTool.java
├── model/                       # Java records: CommitRecord, FileChangeRecord, …
└── util/
    ├── JiraSlugExtractor.java   # regex ^([A-Z]{2,4}-\d+)
    └── HotspotScorer.java       # min-max normalise changeFreq × cyclo (LOC fallback)
```

## Build & test

```bash
# compile
mvn compile

# test (acceptance tests — takes ~10 s due to PMD)
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

### PMD 7 metric rule
- `MetricCollectorRule extends AbstractJavaRule`
- Visits `ASTMethodDeclaration` and `ASTConstructorDeclaration`
- Both extend `AbstractExecutableDeclaration` → implement `ASTExecutableDeclaration`
- `JavaMetrics.CYCLO` / `COGNITIVE_COMPLEXITY` operate on `ASTExecutableDeclaration`
- `MetricsUtil.computeMetric(JavaMetrics.CYCLO, node)` returns `Integer` (nullable)
- `RuleSet.forSingleRule(rule)` keeps the same rule instance (no cloning)
- `ServicesResourceTransformer` in shade config is critical for PMD language providers

### Git indexing
- Default branch: `refs/heads/main` → `refs/heads/master` → `HEAD`
- Root commit (no parent): `EmptyTreeIterator` as old-tree side of DiffFormatter.scan()
- Batch size: 500 commits flushed at once
- co-change map: key `"fileA\0fileB"` (fileA < fileB lexicographic)

### Temporal coupling `since` routing
- No `sinceEpochMs`: fast path via pre-aggregated `file_coupling` table
- With `sinceEpochMs`: CTE-based self-join on `file_changes` (correct but slower)
- README documents the trade-off

## Known risks

| Risk | Status |
|---|---|
| PMD fat JAR ServiceLoader | Mitigated by `ServicesResourceTransformer` — verify with `jar tf … | grep services` |
| JGit root commit NPE | Fixed: `EmptyTreeIterator` for parent-less commits |
| Java 25 + sqlite-jdbc native access | `Enable-Native-Access: ALL-UNNAMED` in MANIFEST |
| `@BindList` empty list | SQLite `IN ()` is invalid — callers guard with `if (paths.isEmpty()) return Map.of()` |
| PMD rule instance cloning | `RuleSet.forSingleRule()` preserves identity in single-analysis runs |
