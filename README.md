# msr-mcp — Mining Software Repository MCP Server

Analyses your git history and exposes four MCP tools that any MCP-compatible AI client
(Claude Desktop, Cursor, …) can call directly inside the repository.

[![CI](https://github.com/mfietz/msr-mcp/actions/workflows/ci.yml/badge.svg)](https://github.com/mfietz/msr-mcp/actions/workflows/ci.yml)

---

## Tools

| Tool | What it does |
|---|---|
| `get_hotspots` | Top files ranked by change frequency × cyclomatic complexity |
| `get_temporal_coupling` | File pairs most often changed together |
| `get_file_commit_history` | Commit history for one file with JIRA slug extraction |
| `refresh_index` | Rebuild the full `.msr/` index |

Only the **default branch** (`main` → `master` → `HEAD`) is indexed.

---

## Prerequisites

* Java 25+ on `PATH`
* [JBang](https://www.jbang.dev/) (for the zero-checkout usage)

---

## Usage

### Option A — JBang (no repo checkout needed)

```bash
# Run directly from the latest GitHub release
jbang msr-mcp@mfietz/msr-mcp
```

Claude Desktop config (`~/Library/Application Support/Claude/claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "msr-mcp": {
      "command": "jbang",
      "args": ["msr-mcp@mfietz/msr-mcp"],
      "workingDirectory": "/path/to/your/git/repo"
    }
  }
}
```

### Option B — Build from source

```bash
git clone https://github.com/mfietz/msr-mcp.git
cd msr-mcp
mvn package -DskipTests
```

Claude Desktop config:

```json
{
  "mcpServers": {
    "msr-mcp": {
      "command": "java",
      "args": ["-jar", "/path/to/msr-mcp/target/msr-mcp-server.jar"],
      "workingDirectory": "/path/to/your/git/repo"
    }
  }
}
```

---

## Index storage

On first startup the server creates a `.msr/` directory in the repo root:

```
.msr/
└── msr.db        # SQLite database (WAL mode)
```

Add `.msr/` to `.gitignore` (it's already in this repo's `.gitignore`).

The index is built once at startup. Call `refresh_index` after a `git pull`
to pick up new commits.

---

## Tool reference

### `get_hotspots`

```
topN          int     Max results (default 20)
sinceEpochMs  long    Only include commits after this timestamp (ms)
extension     string  File extension filter, e.g. ".java" (default)
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

### `get_file_commit_history`

```
filePath      string  (required) Repo-relative path, e.g. "src/Main.java"
limit         int     Max commits (default 50)
sinceEpochMs  long    Only include commits after this timestamp (ms)
```

### `refresh_index`

No arguments. Returns an `IndexResult` JSON object:

```json
{ "status": "ok", "commitsProcessed": 1234, "filesIndexed": 89, "durationMs": 4200, "errorMessage": null }
```

---

## Releasing

Push a tag `v*` to trigger the release workflow which builds and attaches the fat JAR:

```bash
git tag v1.0.0 && git push origin v1.0.0
```
