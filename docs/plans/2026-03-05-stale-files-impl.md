# get_stale_files Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a `get_stale_files` MCP tool that returns files not touched in N days, ranked by `norm(daysSinceLastChange) × norm(complexity)`.

**Architecture:** New tool class following the exact same pattern as `GetHotspotsTool` — query FileChangeDao for stale files, join metrics from FileMetricsDao, score and sort in Java. No schema changes required. All data already exists in `file_changes`, `commits`, and `file_metrics`.

**Tech Stack:** Java 25, JDBI 3 (SqlQuery/BindMethods), SQLite, JUnit 5 + AssertJ, MCP SDK 1.0.0 (Jackson 3).

---

### Background: How the codebase is structured

Read these files before starting — they show the exact patterns to follow:

- `src/main/java/de/mfietz/msrmcp/tool/GetHotspotsTool.java` — tool handler pattern (shared helpers: `ok()`, `error()`, `intArg()`, `stringArg()`)
- `src/main/java/de/mfietz/msrmcp/util/HotspotScorer.java` — min-max normalisation scoring
- `src/main/java/de/mfietz/msrmcp/db/FileChangeDao.java` — JDBI query pattern, `@SqlQuery`, `@Bind`, inner `record`
- `src/main/java/de/mfietz/msrmcp/tool/ToolSchemas.java` — `JsonSchema` definition pattern
- `src/main/java/de/mfietz/msrmcp/tool/ToolRegistry.java` — how tools are wired up
- `src/test/java/de/mfietz/msrmcp/acceptance/GetHotspotsAcceptanceTest.java` — acceptance test pattern

Key convention: `TestRepoBuilder` creates commits starting at `2024-01-01T00:00:00Z`. As of 2026-03-05, those commits are ~790 days old. Use `minDaysStale=365` to include them, `minDaysStale=1000` to exclude them.

---

### Task 1: Write the acceptance test (TDD — write first, it won't compile yet)

**Files:**
- Create: `src/test/java/de/mfietz/msrmcp/acceptance/GetStaleFilesAcceptanceTest.java`

**Step 1: Create the test file**

```java
package de.mfietz.msrmcp.acceptance;

import de.mfietz.msrmcp.db.*;
import de.mfietz.msrmcp.helper.TestRepoBuilder;
import de.mfietz.msrmcp.index.Indexer;
import de.mfietz.msrmcp.model.IndexResult;
import de.mfietz.msrmcp.tool.GetStaleFilesTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance tests for the get_stale_files tool.
 *
 * <p>Test repo layout:
 * <ul>
 *   <li>src/Complex.java — committed once, 2024-01-01 (~790 days ago); has branches (cyclo > 1)
 *   <li>src/Simple.java  — committed once, 2024-01-01 (~790 days ago); linear (cyclo = 1)
 * </ul>
 *
 * <p>Both files are ~790 days old. minDaysStale=365 includes them; minDaysStale=1000 excludes them.
 * Complex.java should rank above Simple.java (higher complexity → higher staleness score).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetStaleFilesAcceptanceTest {

    // Two if-branches → cyclomatic complexity = 3 (PMD counts: method base + 2 ifs)
    static final String COMPLEX_JAVA = """
            public class Complex {
                public int work(int x) {
                    if (x > 10) {
                        if (x > 20) { return 3; }
                        return 2;
                    }
                    return 1;
                }
            }
            """;

    // Linear method → cyclomatic complexity = 1
    static final String SIMPLE_JAVA = """
            public class Simple {
                public String greet(String name) {
                    return "Hello, " + name;
                }
            }
            """;

    Path repoDir;
    Database db;
    GetStaleFilesTool tool;

    @BeforeAll
    void setUp() throws Exception {
        repoDir = new TestRepoBuilder()
                .commit("feat: initial",
                        Map.of("src/Complex.java", COMPLEX_JAVA,
                               "src/Simple.java",  SIMPLE_JAVA))
                .build();

        Files.createDirectories(repoDir.resolve(".msr"));
        db = Database.open(repoDir.resolve(".msr/msr.db"));
        IndexResult result = Indexer.runFull(repoDir, db);
        assertThat(result.status()).isEqualTo("ok");

        tool = new GetStaleFilesTool(db.attach(FileChangeDao.class), db.attach(FileMetricsDao.class));
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void staleFilesAppear_whenMinDaysIsLow() {
        // Commits are ~790 days old; 365-day threshold should include them
        CallToolResult result = tool.handle(Map.of("minDaysStale", 365));
        assertThat(result.isError()).isFalse();

        String json = ((TextContent) result.content().getFirst()).text();
        assertThat(json).contains("Complex.java");
        assertThat(json).contains("Simple.java");
    }

    @Test
    void noFilesReturned_whenMinDaysExceedsAge() {
        // Commits are ~790 days old; 1000-day threshold excludes them all
        CallToolResult result = tool.handle(Map.of("minDaysStale", 1000));
        assertThat(result.isError()).isFalse();

        String json = ((TextContent) result.content().getFirst()).text();
        assertThat(json).isEqualTo("[]");
    }

    @Test
    void complexFileRanksAboveSimpleFile() {
        // Both files are equally old, but Complex.java has higher cyclomatic complexity
        // → higher staleness score → appears first in JSON output
        CallToolResult result = tool.handle(Map.of("minDaysStale", 365));
        String json = ((TextContent) result.content().getFirst()).text();

        int complexIdx = json.indexOf("Complex.java");
        int simpleIdx  = json.indexOf("Simple.java");
        assertThat(complexIdx).isGreaterThanOrEqualTo(0);
        assertThat(simpleIdx).isGreaterThanOrEqualTo(0);
        assertThat(complexIdx).isLessThan(simpleIdx);
    }

    @Test
    void extensionFilter_limitsResults() {
        // Test repo has no .ts files — extension filter should return empty
        CallToolResult result = tool.handle(Map.of("minDaysStale", 365, "extension", ".ts"));
        assertThat(result.isError()).isFalse();

        String json = ((TextContent) result.content().getFirst()).text();
        assertThat(json).isEqualTo("[]");
    }
}
```

**Step 2: Try to compile (expected: compile error — GetStaleFilesTool does not exist yet)**

```bash
cd /Users/marf/workspace/msr-mcp
mvn test-compile 2>&1 | grep "error:"
```

Expected: `error: cannot find symbol` for `GetStaleFilesTool`. This confirms the test drives the implementation.

**Step 3: Commit the failing test**

```bash
git add src/test/java/de/mfietz/msrmcp/acceptance/GetStaleFilesAcceptanceTest.java
git commit -m "test: add GetStaleFilesAcceptanceTest (failing — TDD)"
```

---

### Task 2: Add StaleFileResult model record

**Files:**
- Create: `src/main/java/de/mfietz/msrmcp/model/StaleFileResult.java`

**Step 1: Create the record**

```java
package de.mfietz.msrmcp.model;

public record StaleFileResult(
        String filePath,
        int daysSinceLastChange,
        int ageInDays,
        int loc,
        int cyclomaticComplexity,
        double stalenessScore) {}
```

**Step 2: Compile to verify**

```bash
mvn compile 2>&1 | grep -E "error:|BUILD"
```

Expected: still `error:` for `GetStaleFilesTool` (not yet implemented), but `StaleFileResult` itself compiles.

**Step 3: Commit**

```bash
git add src/main/java/de/mfietz/msrmcp/model/StaleFileResult.java
git commit -m "feat: add StaleFileResult model record"
```

---

### Task 3: Add findStaleFiles query to FileChangeDao

**Files:**
- Modify: `src/main/java/de/mfietz/msrmcp/db/FileChangeDao.java`

**Step 1: Add StaleRow record and findStaleFiles method**

Add the following to `FileChangeDao` — append before the last `}`:

```java
    @SqlQuery("""
            SELECT f.path AS file_path,
                   MAX(c.author_date) AS last_commit_ms,
                   MIN(c.author_date) AS first_commit_ms
            FROM file_changes fc
            JOIN files f   ON f.file_id   = fc.file_id
            JOIN commits c ON c.commit_id = fc.commit_id
            WHERE f.path LIKE :extensionPattern
              AND f.path LIKE :pathFilter
            GROUP BY fc.file_id
            HAVING MAX(c.author_date) <= :cutoffMs
            ORDER BY last_commit_ms ASC
            LIMIT :limit
            """)
    List<StaleRow> findStaleFiles(
            @Bind("cutoffMs") long cutoffMs,
            @Bind("extensionPattern") String extensionPattern,
            @Bind("pathFilter") String pathFilter,
            @Bind("limit") int limit);

    record StaleRow(String filePath, long lastCommitMs, long firstCommitMs) {}
```

Note: `cutoffMs = System.currentTimeMillis() - (minDaysStale × 86_400_000L)`. Files with `MAX(author_date) <= cutoffMs` haven't been touched for at least `minDaysStale` days.

**Step 2: Compile to verify**

```bash
mvn compile 2>&1 | grep -E "error:|BUILD"
```

Expected: still compile error for `GetStaleFilesTool`.

**Step 3: Commit**

```bash
git add src/main/java/de/mfietz/msrmcp/db/FileChangeDao.java
git commit -m "feat: add FileChangeDao.findStaleFiles query"
```

---

### Task 4: Add staleFiles() schema to ToolSchemas

**Files:**
- Modify: `src/main/java/de/mfietz/msrmcp/tool/ToolSchemas.java`

**Step 1: Add staleFiles() method**

Add the following method to `ToolSchemas` — append before the last `}`:

```java
    static JsonSchema staleFiles() {
        return new JsonSchema("object", Map.of(
                "topN", Map.of("type", "integer", "description", "Max results (default 20)",
                        "minimum", 1, "default", 20),
                "minDaysStale", Map.of("type", "integer",
                        "description", "Only include files not changed for at least N days (default 180)",
                        "minimum", 1, "default", 180),
                "extension", Map.of("type", "string",
                        "description", "File extension filter, e.g. \".java\" or \".ts\". Default: all files"),
                "pathFilter", Map.of("type", "string",
                        "description", "SQL LIKE path pattern, e.g. \"src/service/%\". Default: all paths")),
                List.of(), null, null, null);
    }
```

**Step 2: Compile**

```bash
mvn compile 2>&1 | grep -E "error:|BUILD"
```

Expected: still compile error for `GetStaleFilesTool`.

**Step 3: Commit**

```bash
git add src/main/java/de/mfietz/msrmcp/tool/ToolSchemas.java
git commit -m "feat: add ToolSchemas.staleFiles() schema definition"
```

---

### Task 5: Implement GetStaleFilesTool

**Files:**
- Create: `src/main/java/de/mfietz/msrmcp/tool/GetStaleFilesTool.java`

**Step 1: Create the tool class**

The scoring logic mirrors `HotspotScorer` but replaces `changeFrequency` with `daysSinceLastChange` as the first dimension. For files where cyclomatic complexity is -1 (non-Java), LOC is used as a proxy — exactly as `HotspotScorer` does.

```java
package de.mfietz.msrmcp.tool;

import de.mfietz.msrmcp.db.FileChangeDao;
import de.mfietz.msrmcp.db.FileChangeDao.StaleRow;
import de.mfietz.msrmcp.db.FileMetricsDao;
import de.mfietz.msrmcp.model.FileMetricsRecord;
import de.mfietz.msrmcp.model.StaleFileResult;
import io.modelcontextprotocol.spec.McpSchema.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tool: {@code get_stale_files}
 *
 * <p>Returns files that have not been changed for at least N days,
 * ranked by staleness score: norm(daysSinceLastChange) × norm(complexity).
 * Non-Java files use LOC as a complexity proxy.
 *
 * <p>Arguments (all optional):
 * <ul>
 *   <li>{@code minDaysStale} (int, default 180) — minimum days since last change
 *   <li>{@code topN} (int, default 20) — number of results
 *   <li>{@code extension} (String, default "") — file extension filter
 *   <li>{@code pathFilter} (String, default "%") — SQL LIKE path filter
 * </ul>
 */
public final class GetStaleFilesTool {

    static final String NAME = "get_stale_files";
    private static final JsonMapper MAPPER = JsonMapper.shared();

    private final FileChangeDao fileChangeDao;
    private final FileMetricsDao fileMetricsDao;

    public GetStaleFilesTool(FileChangeDao fileChangeDao, FileMetricsDao fileMetricsDao) {
        this.fileChangeDao = fileChangeDao;
        this.fileMetricsDao = fileMetricsDao;
    }

    public CallToolResult handle(Map<String, Object> args) {
        try {
            int topN         = GetHotspotsTool.intArg(args, "topN", 20);
            int minDaysStale = GetHotspotsTool.intArg(args, "minDaysStale", 180);
            String ext       = GetHotspotsTool.stringArg(args, "extension", "");
            String extPattern = "%" + ext;
            String pathFilter = GetHotspotsTool.stringArg(args, "pathFilter", "%");

            long now      = System.currentTimeMillis();
            long cutoffMs = now - (long) minDaysStale * 86_400_000L;

            List<StaleRow> rows =
                    fileChangeDao.findStaleFiles(cutoffMs, extPattern, pathFilter, topN * 3);
            if (rows.isEmpty()) return GetHotspotsTool.ok("[]");

            List<String> paths = rows.stream().map(StaleRow::filePath).toList();
            Map<String, FileMetricsRecord> metricsMap = fileMetricsDao.findByPaths(paths)
                    .stream()
                    .collect(Collectors.toMap(FileMetricsRecord::filePath, r -> r));

            List<StaleFileResult> scored = score(rows, metricsMap, now);
            List<StaleFileResult> result = scored.subList(0, Math.min(topN, scored.size()));

            return GetHotspotsTool.ok(MAPPER.writeValueAsString(result));
        } catch (Exception e) {
            return GetHotspotsTool.error("get_stale_files failed: " + e.getMessage());
        }
    }

    private static List<StaleFileResult> score(
            List<StaleRow> rows,
            Map<String, FileMetricsRecord> metricsMap,
            long now) {

        // ── Pre-compute raw values ──────────────────────────────────────────
        record Computed(StaleRow row, int daysSince, int ageInDays, int loc, int cyclo) {}
        List<Computed> computed = rows.stream().map(r -> {
            FileMetricsRecord m = metricsMap.get(r.filePath());
            int daysSince = (int) ((now - r.lastCommitMs())  / 86_400_000L);
            int ageInDays = (int) ((now - r.firstCommitMs()) / 86_400_000L);
            int loc   = m != null ? m.loc()                  : 0;
            int cyclo = m != null ? m.cyclomaticComplexity() : -1;
            return new Computed(r, daysSince, ageInDays, loc, cyclo);
        }).toList();

        // ── Normalise daysSinceLastChange ───────────────────────────────────
        int minDays = computed.stream().mapToInt(Computed::daysSince).min().orElse(0);
        int maxDays = computed.stream().mapToInt(Computed::daysSince).max().orElse(1);

        // ── Normalise cyclomatic complexity (skip -1 entries) ───────────────
        List<Integer> validCyclo = computed.stream()
                .map(Computed::cyclo).filter(c -> c > 0).toList();
        int minCyclo = validCyclo.isEmpty() ? 0 : Collections.min(validCyclo);
        int maxCyclo = validCyclo.isEmpty() ? 1 : Collections.max(validCyclo);

        // ── Normalise LOC for fallback ──────────────────────────────────────
        List<Integer> validLoc = computed.stream()
                .map(Computed::loc).filter(l -> l > 0).toList();
        int minLoc = validLoc.isEmpty() ? 0 : Collections.min(validLoc);
        int maxLoc = validLoc.isEmpty() ? 1 : Collections.max(validLoc);

        List<StaleFileResult> results = new ArrayList<>(computed.size());
        for (Computed c : computed) {
            double normDays = normalise(c.daysSince(), minDays, maxDays);
            double normComplexity = c.cyclo() > 0
                    ? normalise(c.cyclo(), minCyclo, maxCyclo)
                    : (c.loc() > 0 ? normalise(c.loc(), minLoc, maxLoc) : 0.0);

            results.add(new StaleFileResult(
                    c.row().filePath(), c.daysSince(), c.ageInDays(),
                    c.loc(), c.cyclo(), normDays * normComplexity));
        }

        results.sort(Comparator.comparingDouble(StaleFileResult::stalenessScore).reversed());
        return results;
    }

    private static double normalise(int value, int min, int max) {
        if (max == min) return 1.0;
        return (double) (value - min) / (max - min);
    }

    static Tool toolSpec() {
        return Tool.builder()
                .name(NAME)
                .description("""
                        Returns files not changed for at least N days, ranked by staleness score
                        (age × complexity). Complex files that haven't been touched in a long time
                        score highest. Useful for identifying legacy risk and refactoring candidates.
                        """)
                .inputSchema(ToolSchemas.staleFiles())
                .build();
    }
}
```

**Step 2: Run the acceptance tests**

```bash
mvn test -pl . -Dtest=GetStaleFilesAcceptanceTest -q 2>&1 | tail -20
```

Expected: `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`

If tests fail, check:
- `complexFileRanksAboveSimpleFile`: confirm PMD gives Complex.java cyclo > 1. If PMD returns -1 for both (test classes aren't valid top-level public classes with matching filename), adjust `COMPLEX_JAVA` to be a valid standalone class or check the test file names match class names.
- `noFilesReturned_whenMinDaysExceedsAge`: if this fails, the cutoff math is wrong — verify `cutoffMs` computation.

**Step 3: Commit**

```bash
git add src/main/java/de/mfietz/msrmcp/tool/GetStaleFilesTool.java
git commit -m "feat: implement GetStaleFilesTool"
```

---

### Task 6: Register in ToolRegistry + full test suite

**Files:**
- Modify: `src/main/java/de/mfietz/msrmcp/tool/ToolRegistry.java`

**Step 1: Instantiate and register the tool**

In `buildSpecs()`, add after the `GetOwnershipTool ownership = ...` line:

```java
        GetStaleFilesTool staleFiles = new GetStaleFilesTool(fileChangeDao, fileMetricsDao);
```

In the `List.of(...)`, add after the ownership entry:

```java
                new McpServerFeatures.SyncToolSpecification(
                        GetStaleFilesTool.toolSpec(),
                        (exchange, req) -> staleFiles.handle(req.arguments()))
```

Note: the previous last entry in the list ends with `)` not `),` — add a comma there and the new entry without a trailing comma.

**Step 2: Run the full test suite**

```bash
mvn test 2>&1 | tail -10
```

Expected: `Tests run: 89, Failures: 0, Errors: 0, Skipped: 0` (85 existing + 4 new)

**Step 3: Commit**

```bash
git add src/main/java/de/mfietz/msrmcp/tool/ToolRegistry.java
git commit -m "feat: register get_stale_files tool in ToolRegistry"
```

---

### Task 7: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

**Step 1: Add get_stale_files to the package map**

In the `tool/` section of the package map, add after `GetOwnershipTool.java`:

```
│   ├── GetStaleFilesTool.java         # files not changed in N days × complexity score
```

**Step 2: Add StaleFileResult to the model section**

In the `model/` description line, add `StaleFileResult` to the records list.

**Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md for get_stale_files"
```
