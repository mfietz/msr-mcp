# get_ownership Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a `get_ownership` MCP tool that returns the dominant author and ownership ratio per file, supporting both commit-count and lines-added ownership modes.

**Architecture:** Two new `@SqlQuery` methods on `CommitDao` (one per ownership mode), a new `OwnershipRow` record, and a new `GetOwnershipTool` class following the same pattern as `GetBusFactorTool`. `TestRepoBuilder` is extended to support per-commit author names/emails so multi-author acceptance tests are possible.

**Tech Stack:** Java records, JDBI ConstructorMapper, SQLite CTEs, MCP SDK 1.0.0.

---

### Task 1: Extend TestRepoBuilder for multi-author commits

**Files:**
- Modify: `src/test/java/com/example/msrmcp/helper/TestRepoBuilder.java`

Multi-author tests are impossible without this. The existing `CommitSpec` record hardcodes "Test Author" / "test@example.com".

**Step 1: Read the file**

```bash
# Just read it to understand the current CommitSpec and build() method
```

**Step 2: Update CommitSpec record** — add author fields with defaults

Replace:
```java
private record CommitSpec(String message, Map<String, String> files, Instant when) {}
```

With:
```java
private record CommitSpec(String message, Map<String, String> files, Instant when,
                          String authorName, String authorEmail) {}
```

**Step 3: Update existing `commit()` overloads** — add default author to all existing callers of `new CommitSpec(...)`:

Both `commit()` methods currently create `new CommitSpec(message, new LinkedHashMap<>(files), ...)`.
Change them to `new CommitSpec(message, new LinkedHashMap<>(files), ..., "Test Author", "test@example.com")`.

**Step 4: Add new overloads with explicit author**

After the existing `commit(String message, Map<String, String> files)` method, add:

```java
/** Add a commit with a single file and explicit author. */
public TestRepoBuilder commit(String message, String filePath, String content,
                              String authorName, String authorEmail) {
    return commit(message, Map.of(filePath, content), authorName, authorEmail);
}

/** Add a commit touching multiple files with an explicit author. */
public TestRepoBuilder commit(String message, Map<String, String> files,
                              String authorName, String authorEmail) {
    commits.add(new CommitSpec(message, new LinkedHashMap<>(files),
            Instant.ofEpochSecond(baseEpochSec), authorName, authorEmail));
    baseEpochSec += 3600;
    return this;
}
```

**Step 5: Update `build()` to use per-commit author**

In the `for (CommitSpec spec : commits)` loop, replace the hardcoded `PersonIdent`:

Current:
```java
PersonIdent author = new PersonIdent("Test Author", "test@example.com");
```

This is outside the loop. Move it inside and use spec fields:

```java
for (CommitSpec spec : commits) {
    for (var entry : spec.files().entrySet()) {
        Path filePath = dir.resolve(entry.getKey());
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, entry.getValue());
        git.add().addFilepattern(entry.getKey()).call();
    }
    PersonIdent author = new PersonIdent(spec.authorName(), spec.authorEmail());
    PersonIdent timed = new PersonIdent(author, spec.when());
    git.commit()
            .setMessage(spec.message())
            .setAuthor(timed)
            .setCommitter(timed)
            .call();
}
```

**Step 6: Compile and run existing tests to verify no regression**

```bash
mvn test 2>&1 | tail -10
```

Expected: all existing tests still pass (the default author is preserved).

---

### Task 2: Write the failing acceptance test (RED)

**Files:**
- Create: `src/test/java/com/example/msrmcp/acceptance/GetOwnershipAcceptanceTest.java`

**Step 1: Write the test class**

```java
package com.example.msrmcp.acceptance;

import com.example.msrmcp.db.*;
import com.example.msrmcp.helper.TestRepoBuilder;
import com.example.msrmcp.index.Indexer;
import com.example.msrmcp.tool.GetOwnershipTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance tests for the get_ownership tool.
 *
 * <p>Test repo layout:
 * <ul>
 *   <li>alice@example.com makes 3 commits to Main.java → 75% ownership by commits
 *   <li>bob@example.com makes 1 commit to Main.java → 25% ownership
 *   <li>bob@example.com makes 2 commits to Helper.java → 100% ownership
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetOwnershipAcceptanceTest {

    static final String MAIN_V1 = "public class Main { void v1() {} }";
    static final String MAIN_V2 = "public class Main { void v1() {} void v2() {} }";
    static final String MAIN_V3 = "public class Main { void v1() {} void v2() {} void v3() {} }";
    static final String MAIN_V4 = "public class Main { void v1() {} void v2() {} void v3() {} void v4() {} }";
    static final String HELPER  = "public class Helper {}";
    static final String HELPER2 = "public class Helper { void help() {} }";

    Path repoDir;
    Database db;
    GetOwnershipTool ownershipTool;

    @BeforeAll
    void setUp() throws Exception {
        repoDir = new TestRepoBuilder()
                // Alice: 3 commits on Main.java
                .commit("init",   "src/Main.java", MAIN_V1, "Alice", "alice@example.com")
                .commit("feat v2","src/Main.java", MAIN_V2, "Alice", "alice@example.com")
                .commit("feat v3","src/Main.java", MAIN_V3, "Alice", "alice@example.com")
                // Bob: 1 commit on Main.java, 2 on Helper.java
                .commit("feat v4","src/Main.java", MAIN_V4, "Bob",   "bob@example.com")
                .commit("helper", "src/Helper.java", HELPER,  "Bob",   "bob@example.com")
                .commit("helper2","src/Helper.java", HELPER2, "Bob",   "bob@example.com")
                .build();

        Files.createDirectories(repoDir.resolve(".msr"));
        db = Database.open(repoDir.resolve(".msr/msr.db"));

        Indexer.runFull(repoDir, db);

        CommitDao commitDao = db.attach(CommitDao.class);
        ownershipTool = new GetOwnershipTool(commitDao);
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    @Test
    void ownershipByCommits_mainJava_aliceOwns75Percent() {
        CallToolResult result = ownershipTool.handle(Map.of(
                "topN", 10, "ownershipBy", "commits"));
        String json = ((TextContent) result.content().getFirst()).text();

        assertThat(json).contains("alice@example.com");
        assertThat(json).contains("src/Main.java");
        // Alice has 3/4 = 0.75 ownership of Main.java
        assertThat(json).contains("0.75");
    }

    @Test
    void ownershipByCommits_helperJava_bobOwns100Percent() {
        CallToolResult result = ownershipTool.handle(Map.of(
                "topN", 10, "ownershipBy", "commits"));
        String json = ((TextContent) result.content().getFirst()).text();

        assertThat(json).contains("bob@example.com");
        assertThat(json).contains("src/Helper.java");
        assertThat(json).contains("1.0");
    }

    @Test
    void minOwnership_filtersLowOwnership() {
        // minOwnership=0.9 should only return Helper.java (Bob 100%), not Main.java (Alice 75%)
        CallToolResult result = ownershipTool.handle(Map.of(
                "topN", 10, "ownershipBy", "commits", "minOwnership", 0.9));
        String json = ((TextContent) result.content().getFirst()).text();

        assertThat(json).contains("src/Helper.java");
        assertThat(json).doesNotContain("src/Main.java");
    }

    @Test
    void ownershipByLines_isSupported() {
        CallToolResult result = ownershipTool.handle(Map.of(
                "topN", 10, "ownershipBy", "lines"));
        assertThat(result.isError()).isFalse();
        String json = ((TextContent) result.content().getFirst()).text();
        assertThat(json).contains("ownershipRatio");
    }

    @Test
    void invalidOwnershipBy_returnsError() {
        CallToolResult result = ownershipTool.handle(Map.of("ownershipBy", "banana"));
        assertThat(result.isError()).isTrue();
    }
}
```

**Step 2: Run to verify it fails**

```bash
mvn test -Dtest="GetOwnershipAcceptanceTest" 2>&1 | tail -15
```

Expected: compilation error — `GetOwnershipTool` does not exist yet.

---

### Task 3: Add OwnershipRow + DAO methods to CommitDao

**Files:**
- Modify: `src/main/java/com/example/msrmcp/db/CommitDao.java`

**Step 1: Add `OwnershipRow` record** (after `BusFactorRow`):

```java
record OwnershipRow(String filePath, String topAuthorEmail, String topAuthorName,
                    long topAuthorAmount, long totalAmount, double ownershipRatio) {}
```

**Step 2: Add `findOwnershipByCommits`** (before the record definitions):

```java
@SqlQuery("""
        WITH file_author_counts AS (
          SELECT fc.file_id, c.author_email, c.author_name, COUNT(*) AS author_amount
          FROM file_changes fc
          JOIN commits c ON c.commit_id = fc.commit_id
          WHERE (:sinceEpochMs IS NULL OR c.author_date >= :sinceEpochMs)
          GROUP BY fc.file_id, c.author_email
        ),
        file_totals AS (
          SELECT file_id,
                 SUM(author_amount) AS total_amount,
                 MAX(author_amount) AS max_author_amount
          FROM file_author_counts GROUP BY file_id
        )
        SELECT f.path AS file_path,
               fac.author_email AS top_author_email,
               fac.author_name  AS top_author_name,
               fac.author_amount AS top_author_amount,
               ft.total_amount,
               CAST(fac.author_amount AS REAL) / ft.total_amount AS ownership_ratio
        FROM file_totals ft
        JOIN file_author_counts fac ON fac.file_id = ft.file_id
                                    AND fac.author_amount = ft.max_author_amount
        JOIN files f ON f.file_id = ft.file_id
        WHERE f.path LIKE :extensionPattern
          AND f.path LIKE :pathFilter
          AND CAST(fac.author_amount AS REAL) / ft.total_amount >= :minOwnership
        ORDER BY ownership_ratio DESC
        LIMIT :topN
        """)
List<OwnershipRow> findOwnershipByCommits(
        @Bind("sinceEpochMs") Long sinceEpochMs,
        @Bind("extensionPattern") String extensionPattern,
        @Bind("pathFilter") String pathFilter,
        @Bind("minOwnership") double minOwnership,
        @Bind("topN") int topN);
```

**Step 3: Add `findOwnershipByLines`** (same structure, `SUM(fc.lines_added)` instead of `COUNT(*)`):

```java
@SqlQuery("""
        WITH file_author_counts AS (
          SELECT fc.file_id, c.author_email, c.author_name,
                 SUM(fc.lines_added) AS author_amount
          FROM file_changes fc
          JOIN commits c ON c.commit_id = fc.commit_id
          WHERE (:sinceEpochMs IS NULL OR c.author_date >= :sinceEpochMs)
          GROUP BY fc.file_id, c.author_email
        ),
        file_totals AS (
          SELECT file_id,
                 SUM(author_amount) AS total_amount,
                 MAX(author_amount) AS max_author_amount
          FROM file_author_counts
          GROUP BY file_id
          HAVING total_amount > 0
        )
        SELECT f.path AS file_path,
               fac.author_email AS top_author_email,
               fac.author_name  AS top_author_name,
               fac.author_amount AS top_author_amount,
               ft.total_amount,
               CAST(fac.author_amount AS REAL) / ft.total_amount AS ownership_ratio
        FROM file_totals ft
        JOIN file_author_counts fac ON fac.file_id = ft.file_id
                                    AND fac.author_amount = ft.max_author_amount
        JOIN files f ON f.file_id = ft.file_id
        WHERE f.path LIKE :extensionPattern
          AND f.path LIKE :pathFilter
          AND CAST(fac.author_amount AS REAL) / ft.total_amount >= :minOwnership
        ORDER BY ownership_ratio DESC
        LIMIT :topN
        """)
List<OwnershipRow> findOwnershipByLines(
        @Bind("sinceEpochMs") Long sinceEpochMs,
        @Bind("extensionPattern") String extensionPattern,
        @Bind("pathFilter") String pathFilter,
        @Bind("minOwnership") double minOwnership,
        @Bind("topN") int topN);
```

**Step 4: Compile**

```bash
mvn compile 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

---

### Task 4: Register OwnershipRow in Database

**Files:**
- Modify: `src/main/java/com/example/msrmcp/db/Database.java`

**Step 1: Add ConstructorMapper registration**

In `Database.open()`, after the line that registers `CommitDao.BusFactorRow.class`, add:

```java
jdbi.registerRowMapper(ConstructorMapper.factory(CommitDao.OwnershipRow.class));
```

**Step 2: Compile**

```bash
mvn compile 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

---

### Task 5: Add ToolSchemas entry + GetOwnershipTool

**Files:**
- Modify: `src/main/java/com/example/msrmcp/tool/ToolSchemas.java`
- Create: `src/main/java/com/example/msrmcp/tool/GetOwnershipTool.java`

**Step 1: Add `ownership()` schema to ToolSchemas** (before the `empty()` method):

```java
static JsonSchema ownership() {
    return new JsonSchema("object", Map.of(
            "topN", Map.of("type", "integer", "description", "Max results (default 20)",
                    "minimum", 1, "default", 20),
            "ownershipBy", Map.of("type", "string",
                    "description", "How to measure ownership: \"commits\" (default) or \"lines\"",
                    "enum", List.of("commits", "lines")),
            "minOwnership", Map.of("type", "number",
                    "description", "Min ownership ratio 0–1 (default 0.0 = all files)",
                    "minimum", 0.0, "maximum", 1.0, "default", 0.0),
            "extension", Map.of("type", "string",
                    "description", "File extension filter, e.g. \".java\". Default: all files"),
            "pathFilter", Map.of("type", "string",
                    "description", "SQL LIKE path pattern, e.g. \"src/service/%\""),
            "sinceEpochMs", Map.of("type", "integer",
                    "description", "Only include commits after this Unix timestamp in ms")),
            List.of(), null, null, null);
}
```

Note: `List` import is already present in ToolSchemas.

**Step 2: Create GetOwnershipTool**

```java
package com.example.msrmcp.tool;

import com.example.msrmcp.db.CommitDao;
import com.example.msrmcp.db.CommitDao.OwnershipRow;
import io.modelcontextprotocol.spec.McpSchema.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static com.example.msrmcp.tool.GetHotspotsTool.*;

/**
 * MCP tool: {@code get_ownership}
 *
 * <p>Returns the dominant author and ownership ratio per file.
 * Ownership is measured either by commit count or by lines added.
 */
public final class GetOwnershipTool {

    static final String NAME = "get_ownership";
    private static final JsonMapper MAPPER = JsonMapper.shared();

    private final CommitDao commitDao;

    public GetOwnershipTool(CommitDao commitDao) {
        this.commitDao = commitDao;
    }

    public CallToolResult handle(Map<String, Object> args) {
        try {
            int    topN          = intArg(args, "topN", 20);
            String ownershipBy   = stringArg(args, "ownershipBy", "commits");
            double minOwnership  = doubleArg(args, "minOwnership", 0.0);
            String ext           = stringArg(args, "extension", "");
            String extPattern    = "%" + ext;
            String pathFilter    = stringArg(args, "pathFilter", "%");
            Long   sinceEpochMs  = longArg(args, "sinceEpochMs");

            List<OwnershipRow> rows = switch (ownershipBy) {
                case "commits" -> commitDao.findOwnershipByCommits(
                        sinceEpochMs, extPattern, pathFilter, minOwnership, topN);
                case "lines"   -> commitDao.findOwnershipByLines(
                        sinceEpochMs, extPattern, pathFilter, minOwnership, topN);
                default -> { return error("ownershipBy must be \"commits\" or \"lines\""); }
            };

            return ok(MAPPER.writeValueAsString(rows));
        } catch (Exception e) {
            return error("get_ownership failed: " + e.getMessage());
        }
    }

    static Tool toolSpec() {
        return Tool.builder()
                .name(NAME)
                .description("""
                        Returns the dominant author and ownership ratio for each file.
                        ownershipBy="commits" (default): top author's commits / total commits.
                        ownershipBy="lines": top author's lines added / total lines added.
                        Useful for identifying knowledge silos and ownership distribution.
                        """)
                .inputSchema(ToolSchemas.ownership())
                .build();
    }

    private static double doubleArg(Map<String, Object> args, String key, double def) {
        Object v = args.get(key);
        if (v == null) return def;
        return ((Number) v).doubleValue();
    }
}
```

**Step 3: Compile**

```bash
mvn compile 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

---

### Task 6: Register in ToolRegistry + verify + docs + commit + push

**Files:**
- Modify: `src/main/java/com/example/msrmcp/tool/ToolRegistry.java`
- Modify: `CLAUDE.md`

**Step 1: Add to ToolRegistry**

In `buildSpecs()`, after `GetBusFactorTool busFactor = ...`:

```java
GetOwnershipTool ownership = new GetOwnershipTool(commitDao);
```

In the `List.of(...)`, after the `GetBusFactorTool` entry:

```java
new McpServerFeatures.SyncToolSpecification(
        GetOwnershipTool.toolSpec(),
        (exchange, req) -> ownership.handle(req.arguments())),
```

**Step 2: Run full test suite**

```bash
mvn test 2>&1 | tail -10
```

Expected: all tests pass (including the new `GetOwnershipAcceptanceTest`).

**Step 3: Update CLAUDE.md**

In the tool list under `tool/`, add after `GetBusFactorTool.java`:
```
│   ├── GetOwnershipTool.java          # dominant author + ownershipRatio per file; ownershipBy=commits|lines
```

In the package map model section, add `OwnershipRow` to the CommitDao records note.

**Step 4: Commit**

```bash
git add \
  src/test/java/com/example/msrmcp/helper/TestRepoBuilder.java \
  src/test/java/com/example/msrmcp/acceptance/GetOwnershipAcceptanceTest.java \
  src/main/java/com/example/msrmcp/db/CommitDao.java \
  src/main/java/com/example/msrmcp/db/Database.java \
  src/main/java/com/example/msrmcp/tool/ToolSchemas.java \
  src/main/java/com/example/msrmcp/tool/GetOwnershipTool.java \
  src/main/java/com/example/msrmcp/tool/ToolRegistry.java \
  CLAUDE.md
git commit -m "feat: add get_ownership tool — dominant author + ownership ratio per file

Supports ownershipBy=commits (default) and ownershipBy=lines.
Filterable by extension, pathFilter, minOwnership, sinceEpochMs.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

**Step 5: Push**

```bash
git push
```
