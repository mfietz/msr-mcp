# msr-mcp — Mining Software Repository MCP Server

Analyses your git history and exposes MCP tools that any MCP-compatible AI client
(Claude Desktop, Cursor, …) can call directly inside the repository.

[![CI](https://github.com/mfietz/msr-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/mfietz/msr-mcp/actions/workflows/ci.yml)

---

## Tools

| Tool | What it does |
|---|---|
| `get_summary` | Repo overview: commit count, date range, top changed files |
| `get_hotspots` | Top files ranked by change frequency × complexity (supports path/extension filter) |
| `get_temporal_coupling` | File pairs most often changed together |
| `get_file_coupling` | Coupling partners for a specific file |
| `get_coupling_clusters` | Groups of files that frequently change together (co-change clusters) |
| `get_file_commit_history` | Commit history for one file with JIRA slug extraction and filter |
| `get_file_authors` | Authors ranked by commit count for a specific file (knowledge owners) |
| `get_bus_factor` | Files where one author dominates commits (knowledge silos) |
| `get_ownership` | Dominant author per file by commit count or lines |
| `get_churn` | Top files ranked by total lines added + deleted |
| `get_stale_files` | Files not changed for N days, weighted by complexity |
| `get_index_status` | Current indexing state (useful when index is still being built) |
| `refresh_index` | Rebuild the full `.msr/` index from scratch |

Only the **default branch** (`main` → `master` → `HEAD`) is indexed.

### Language support

Full metrics (change frequency, LOC, cyclomatic & cognitive complexity) are available for **Java** only — PMD drives the complexity analysis. PMD 7's Kotlin support does not yet expose a metrics API, so Kotlin gets LOC only. All other text-based languages (TypeScript, Go, Python, …) also get change frequency and LOC; binary files are skipped entirely.

---

## Prerequisites

* Java 25+ on `PATH`
* [JBang](https://www.jbang.dev/) (for the zero-checkout usage)

---

## Usage

### Option A — Claude CLI (`claude mcp add`)

The quickest way to register msr-mcp in Claude Code.

**From the latest GitHub release** (recommended):

```bash
# Download the JAR once
curl -L https://github.com/mfietz/msr-mcp/releases/latest/download/msr-mcp-server.jar \
     -o ~/bin/msr-mcp-server.jar

# Register for all your projects (user scope)
claude mcp add --scope user msr-mcp -- java -jar ~/bin/msr-mcp-server.jar
```

**From a local build**:

```bash
claude mcp add --scope user msr-mcp -- java -jar /path/to/msr-mcp/target/msr-mcp-server.jar
```

The server inherits the working directory from Claude Code, which is the repo root when you run `claude` inside a git repository. Use `--scope project` instead to check the config into the repo as `.mcp.json` (note: the JAR path will then be machine-specific).

---

### Option B — JBang (no checkout needed)

```bash
jbang msr-mcp@mfietz/msr-mcp
```

### Option C — Build from source

```bash
git clone https://github.com/mfietz/msr-mcp.git
cd msr-mcp
mvn package -DskipTests
# JAR: target/msr-mcp-server.jar
```

### Other MCP clients (Claude Desktop, Cursor, …)

Add to your client's MCP config, pointing `workingDirectory` at your git repo:

```json
{
  "mcpServers": {
    "msr-mcp": {
      "command": "java",
      "args": ["-jar", "/path/to/msr-mcp-server.jar"],
      "workingDirectory": "/path/to/your/git/repo"
    }
  }
}
```

For JBang replace `"command": "java"` / `"args": ["-jar", "..."]` with `"command": "jbang"` / `"args": ["msr-mcp@mfietz/msr-mcp"]`.

---

## Index storage

On first startup the server creates a `.msr/` directory in the repo root:

```
.msr/
└── msr.db        # SQLite database (WAL mode)
```

Add `.msr/` to `.gitignore` (it's already in this repo's `.gitignore`).

The index is updated **incrementally on every startup** — only commits newer than the last indexed tip are processed. Call `refresh_index` to force a full rebuild from scratch.

Renamed files are tracked: history from before the rename is carried forward to the new path. Deleted files are pruned from the complexity metrics table so they no longer appear in hotspot or churn results.

---

## Tool reference

### `get_summary`

No arguments. Returns a repo overview:

```json
{
  "totalCommits": 312,
  "uniqueAuthors": 8,
  "totalFilesTracked": 94,
  "filesWithMetrics": 42,
  "earliestCommitMs": 1704067200000,
  "latestCommitMs": 1741046400000,
  "topChangedFiles": [
    { "filePath": "src/main/java/com/example/Foo.java", "changeFrequency": 47 }
  ],
  "topAuthors": [
    { "name": "Alice", "email": "alice@example.com", "commits": 120 }
  ],
  "languageDistribution": [
    { "extension": ".java", "fileCount": 72 },
    { "extension": ".xml",  "fileCount": 12 }
  ]
}
```

### `get_hotspots`

```
topN          int     Max results (default 20)
sinceEpochMs  long    Only include commits after this timestamp (ms)
extension     string  File extension filter, e.g. ".java". Default: all files
pathFilter    string  SQL LIKE path pattern, e.g. "src/service/%". Default: all paths
```

Returns a JSON array sorted by `hotspotScore` descending:

```json
[
  {
    "path": "src/main/java/com/example/Foo.java",
    "changeFrequency": 47,
    "linesOfCode": 312,
    "cyclomaticComplexity": 18,
    "cognitiveComplexity": 24,
    "hotspotScore": 0.93
  }
]
```

### `get_temporal_coupling`

```
topN          int     Max results (default 20)
minCoupling   double  Min coupling ratio 0–1 (default 0.3)
fileFilter    string  SQL LIKE pattern, e.g. "%.java"
sinceEpochMs  long    Time window; triggers slower dynamic query when set
```

Without `sinceEpochMs` the fast pre-aggregated `file_coupling` table is used.
With `sinceEpochMs` a CTE-based query runs directly on `file_changes` (slower).

### `get_file_coupling`

```
filePath      string  (required) Repo-relative path, e.g. "src/Main.java"
topN          int     Max partner files (default 10)
minCoupling   double  Min coupling ratio 0–1 (default 0.1)
sinceEpochMs  long    Time window; triggers slower dynamic query when set
```

Without `sinceEpochMs` the fast pre-aggregated `file_coupling` table is used.
With `sinceEpochMs` a CTE-based query runs directly on `file_changes` (slower).

Returns the files most frequently changed together with the given file:

```json
[
  {
    "partnerPath": "src/service/OrderService.java",
    "coChanges": 12,
    "targetTotalChanges": 15,
    "partnerTotalChanges": 13,
    "couplingRatio": 0.92
  }
]
```

### `get_coupling_clusters`

Identifies groups of files that frequently change together, using bidirectional coupling
(`co_changes / MAX(total_a, total_b)`) to exclude hub files that co-change with everything.

Two modes:
- **Global scan** (no `filePath`): Union-Find over all edges ≥ `minCoupling`, returns all clusters sorted by average coupling.
- **Single-file lookup** (`filePath` set): recursive graph traversal returns only the cluster containing that file — much faster for targeted queries.

```
filePath        string  If set: return the cluster for this file. If absent: return all clusters.
minCoupling     double  Min bidirectional coupling ratio 0–1 (default 0.3)
minClusterSize  int     Min files per cluster (default 3, global mode only)
maxClusterSize  int     Max files per cluster; excludes god-clusters (no default)
pathFilter      string  SQL LIKE pattern; keeps clusters where ≥1 file matches, e.g. "src/auth/%"
topN            int     Max clusters to return (default 20, global mode only)
sinceEpochMs    long    Time window; triggers dynamic query when set
```

```json
[
  {
    "clusterIndex": 1,
    "files": ["src/auth/AuthFilter.java", "src/auth/LoginService.java", "src/auth/TokenStore.java"],
    "edges": [
      { "fileA": "src/auth/AuthFilter.java", "fileB": "src/auth/LoginService.java",
        "coChanges": 18, "couplingRatio": 0.82 }
    ],
    "avgCoupling": 0.76
  }
]
```

### `get_file_authors`

```
filePath      string  (required) Repo-relative path, e.g. "src/Main.java"
topN          int     Max authors to return (default 10)
sinceEpochMs  long    Only include commits after this timestamp (ms)
```

Returns authors ranked by commit count for the given file:

```json
[
  { "authorEmail": "alice@example.com", "authorName": "Alice", "commitCount": 42 },
  { "authorEmail": "bob@example.com",   "authorName": "Bob",   "commitCount":  7 }
]
```

### `get_bus_factor`

```
topN          int     Max results (default 20)
threshold     double  Min dominance ratio 0–1 (default 0.75)
pathFilter    string  SQL LIKE path pattern, e.g. "src/service/%"
sinceEpochMs  long    Only include commits after this timestamp (ms)
```

Returns files where one author made ≥ `threshold` of all commits, sorted by `dominanceRatio` descending:

```json
[
  {
    "filePath": "src/core/Engine.java",
    "topAuthorEmail": "alice@example.com",
    "topAuthorName": "Alice",
    "topAuthorCommits": 38,
    "totalCommits": 41,
    "dominanceRatio": 0.93
  }
]
```

### `get_churn`

```
topN          int     Max results (default 20)
sinceEpochMs  long    Only include commits after this timestamp (ms)
extension     string  File extension filter, e.g. ".java". Default: all files
pathFilter    string  SQL LIKE path pattern, e.g. "src/service/%". Default: all paths
```

Returns files sorted by total churn (lines added + lines deleted) descending:

```json
[
  {
    "filePath": "src/main/java/com/example/Foo.java",
    "linesAdded": 840,
    "linesDeleted": 310,
    "churn": 1150,
    "changeFrequency": 47
  }
]
```

### `get_ownership`

```
topN          int     Max results (default 20)
ownershipBy   string  Measure by "commits" (default) or "lines"
minOwnership  double  Min ownership ratio 0–1 (default 0.0)
extension     string  File extension filter, e.g. ".java". Default: all files
pathFilter    string  SQL LIKE path pattern, e.g. "src/service/%". Default: all paths
sinceEpochMs  long    Only include commits after this timestamp (ms)
```

Returns the dominant author per file, sorted by ownership ratio descending:

```json
[
  {
    "filePath": "src/core/Engine.java",
    "ownerEmail": "alice@example.com",
    "ownerName": "Alice",
    "ownerCount": 38,
    "totalCount": 41,
    "ownershipRatio": 0.93
  }
]
```

### `get_stale_files`

Files that have not been changed for a long time, weighted by complexity — useful for finding neglected code that may have rotted.

```
topN          int     Max results (default 20)
minDaysStale  int     Only include files not changed for at least N days (default 180)
extension     string  File extension filter, e.g. ".java". Default: all files
pathFilter    string  SQL LIKE path pattern, e.g. "src/service/%". Default: all paths
```

Returns files sorted by staleness score (`daysSinceLastChange × complexity`) descending:

```json
[
  {
    "filePath": "src/legacy/OldParser.java",
    "daysSinceLastChange": 540,
    "ageInDays": 1200,
    "linesOfCode": 420,
    "cyclomaticComplexity": 31,
    "stalenessScore": 0.95
  }
]
```

### `get_index_status`

No arguments. Returns the current state of the background index:

```json
{ "status": "READY", "startedAtMs": 1741046400000, "elapsedMs": 4200, "errorMessage": null }
```

`status` is one of `NOT_STARTED`, `INDEXING`, `READY`, or `ERROR`. Tools return an error response while status is not `READY`.

### `get_file_commit_history`

```
filePath      string  (required) Repo-relative path, e.g. "src/Main.java"
limit         int     Max commits (default 50)
sinceEpochMs  long    Only include commits after this timestamp (ms)
jiraSlug      string  Filter by JIRA slug; supports LIKE patterns, e.g. "PROJ-123" or "PROJ-%"
```

### `refresh_index`

No arguments. Clears and rebuilds the entire index from scratch. Returns:

```json
{ "status": "ok", "commitsProcessed": 1234, "filesIndexed": 89, "durationMs": 4200, "errorMessage": null }
```

---

## Further reading

### Model Context Protocol
- [MCP official documentation](https://modelcontextprotocol.io) — spec, concepts, quickstart
- [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) — source & examples used by this server

### Mining Software Repositories — books
- Adam Tornhill, *Your Code as a Crime Scene* (Pragmatic Programmers, 2015) — introduced hotspot analysis and temporal coupling as practical refactoring guides
- Adam Tornhill, *Software Design X-Rays* (Pragmatic Programmers, 2018) — extends the method with team-level and architectural analyses

### Tools implementing similar ideas
- [CodeScene](https://codescene.com) — Tornhill's commercial platform; useful reference for what MSR-driven insights look like at scale
- [code-maat](https://github.com/adamtornhill/code-maat) — Tornhill's original open-source command-line MSR tool (Clojure)

### Academic background
- [MSR conference series](https://www.msrconf.org/) — the primary academic venue for Mining Software Repositories research
- [CodeCharta](https://maibornwolff.github.io/codecharta/) — open-source 3-D visualisation of code metrics including hotspots

---

## Releasing

Push a tag `v*` to trigger the release workflow which builds and attaches the fat JAR:

```bash
git tag v1.0.0 && git push origin v1.0.0
```
