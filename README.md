# msr-mcp — Mining Software Repository MCP Server

Analyses your git history and exposes four MCP tools that any MCP-compatible AI client
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
| `get_file_commit_history` | Commit history for one file with JIRA slug extraction |
| `refresh_index` | Rebuild the full `.msr/` index from scratch |

Only the **default branch** (`main` → `master` → `HEAD`) is indexed.

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

---

## Tool reference

### `get_summary`

No arguments. Returns a repo overview:

```json
{
  "totalCommits": 312,
  "totalFilesTracked": 94,
  "filesWithMetrics": 42,
  "earliestCommitMs": 1704067200000,
  "latestCommitMs": 1741046400000,
  "topChangedFiles": [
    { "filePath": "src/main/java/com/example/Foo.java", "changeFrequency": 47 }
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
```

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

### `get_file_commit_history`

```
filePath      string  (required) Repo-relative path, e.g. "src/Main.java"
limit         int     Max commits (default 50)
sinceEpochMs  long    Only include commits after this timestamp (ms)
```

### `refresh_index`

No arguments. Clears and rebuilds the entire index from scratch. Returns:

```json
{ "status": "ok", "commitsProcessed": 1234, "filesIndexed": 89, "durationMs": 4200, "errorMessage": null }
```

---

## Releasing

Push a tag `v*` to trigger the release workflow which builds and attaches the fat JAR:

```bash
git tag v1.0.0 && git push origin v1.0.0
```
