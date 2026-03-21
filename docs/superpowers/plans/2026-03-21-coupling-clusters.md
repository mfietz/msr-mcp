# Coupling Clusters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `get_coupling_clusters` MCP tool that identifies groups of files forming co-change modules, using bidirectional coupling ratio (MAX normalization) and connected-component graph analysis.

**Architecture:** Two modes — global scan (Union-Find over all edges from pre-aggregated `file_coupling` table) and single-file lookup (recursive CTE in SQLite traverses only the target file's component). Both support an optional `sinceEpochMs` time window that switches to a CTE-based `file_changes` query. MAX normalization (`co_changes / MAX(total_a, total_b)`) naturally excludes hub files that co-change with many unrelated files.

**Tech Stack:** Java 25, JDBI 3 (SqlObject + ConstructorMapper), SQLite (recursive CTE), JUnit 5 + AssertJ, MCP SDK 1.0.0.

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `src/main/java/de/mfietz/msrmcp/model/ClusterEdge.java` | Create | Record: one coupling edge within a cluster |
| `src/main/java/de/mfietz/msrmcp/model/CouplingClusterResult.java` | Create | Record: one cluster (files + edges + avgCoupling) |
| `src/main/java/de/mfietz/msrmcp/db/FileCouplingDao.java` | Modify | Add 4 new DAO queries for clustering |
| `src/main/java/de/mfietz/msrmcp/tool/GetCouplingClustersTool.java` | Create | Tool: dispatch mode, Union-Find, cluster serialization |
| `src/main/java/de/mfietz/msrmcp/tool/ToolSchemas.java` | Modify | Add `couplingClusters()` JSON schema |
| `src/main/java/de/mfietz/msrmcp/tool/ToolRegistry.java` | Modify | Instantiate and register the new tool |
| `src/test/java/de/mfietz/msrmcp/acceptance/GetCouplingClustersAcceptanceTest.java` | Create | Acceptance tests for all modes |

---

## Task 1: Model Records

**Files:**
- Create: `src/main/java/de/mfietz/msrmcp/model/ClusterEdge.java`
- Create: `src/main/java/de/mfietz/msrmcp/model/CouplingClusterResult.java`

- [ ] **Step 1: Create `ClusterEdge.java`**

```java
package de.mfietz.msrmcp.model;

/** One coupling edge between two files within a co-change cluster. */
public record ClusterEdge(
        String fileA, String fileB, int coChanges, double couplingRatio) {}
```

- [ ] **Step 2: Create `CouplingClusterResult.java`**

```java
package de.mfietz.msrmcp.model;

import java.util.List;

/** A group of files that frequently change together, identified by co-change graph analysis. */
public record CouplingClusterResult(
        int clusterIndex,
        List<String> files,
        List<ClusterEdge> edges,
        double avgCoupling) {}
```

- [ ] **Step 3: Verify compilation**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS, no errors.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/de/mfietz/msrmcp/model/ClusterEdge.java \
        src/main/java/de/mfietz/msrmcp/model/CouplingClusterResult.java
git commit -m "feat: add ClusterEdge and CouplingClusterResult model records"
```

---

## Task 2: Failing Acceptance Tests

**Files:**
- Create: `src/test/java/de/mfietz/msrmcp/acceptance/GetCouplingClustersAcceptanceTest.java`

The test repo has two isolated clusters:
- `auth/Login.java` + `auth/Auth.java` co-change in 3 commits
- `repo/UserRepo.java` + `repo/OrderRepo.java` co-change in 3 commits (different commits, no cross-coupling)

- [ ] **Step 1: Create the acceptance test**

```java
package de.mfietz.msrmcp.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.mfietz.msrmcp.db.Database;
import de.mfietz.msrmcp.db.FileCouplingDao;
import de.mfietz.msrmcp.helper.TestRepoBuilder;
import de.mfietz.msrmcp.index.IndexTracker;
import de.mfietz.msrmcp.index.Indexer;
import de.mfietz.msrmcp.tool.GetCouplingClustersTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.*;

/**
 * Acceptance tests for get_coupling_clusters.
 *
 * <p>Test repo: auth/Login+Auth always co-change (3 commits); repo/UserRepo+OrderRepo always
 * co-change (3 different commits). No cross-cluster co-changes. Expected: two distinct clusters
 * with MAX-normalized couplingRatio=1.0 each.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetCouplingClustersAcceptanceTest {

    Path repoDir;
    Database db;
    GetCouplingClustersTool tool;
    IndexTracker tracker;

    @BeforeAll
    void setUp() throws Exception {
        repoDir =
                new TestRepoBuilder()
                        .commit(
                                "init auth",
                                Map.of(
                                        "auth/Login.java", "class Login {}",
                                        "auth/Auth.java", "class Auth {}"))
                        .commit(
                                "feat auth",
                                Map.of(
                                        "auth/Login.java", "class Login { void v(){} }",
                                        "auth/Auth.java", "class Auth { void v(){} }"))
                        .commit(
                                "fix auth",
                                Map.of(
                                        "auth/Login.java", "class Login { void v2(){} }",
                                        "auth/Auth.java", "class Auth { void v2(){} }"))
                        .commit(
                                "init repo",
                                Map.of(
                                        "repo/UserRepo.java", "class UserRepo {}",
                                        "repo/OrderRepo.java", "class OrderRepo {}"))
                        .commit(
                                "feat repo",
                                Map.of(
                                        "repo/UserRepo.java", "class UserRepo { void v(){} }",
                                        "repo/OrderRepo.java", "class OrderRepo { void v(){} }"))
                        .commit(
                                "fix repo",
                                Map.of(
                                        "repo/UserRepo.java", "class UserRepo { void v2(){} }",
                                        "repo/OrderRepo.java", "class OrderRepo { void v2(){} }"))
                        .build();

        Files.createDirectories(repoDir.resolve(".msr"));
        db = Database.open(repoDir.resolve(".msr/msr.db"));
        Indexer.runFull(repoDir, db);

        tracker = new IndexTracker();
        tracker.markReady(0);
        tool = new GetCouplingClustersTool(db.attach(FileCouplingDao.class), tracker);
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    // --- Mode 1: global scan ---

    @Test
    void globalMode_returnsTwoClusters() {
        String json = text(tool.handle(Map.of()));
        assertThat(json).contains("\"clusterIndex\":1");
        assertThat(json).contains("\"clusterIndex\":2");
        assertThat(json).contains("auth/Login.java");
        assertThat(json).contains("auth/Auth.java");
        assertThat(json).contains("repo/UserRepo.java");
        assertThat(json).contains("repo/OrderRepo.java");
    }

    @Test
    void globalMode_eachClusterHasEdgesAndFullCoupling() {
        String json = text(tool.handle(Map.of()));
        // Each pair co-changed in 3 commits with ratio 1.0 (MAX normalization)
        assertThat(json).contains("\"coChanges\":3");
        assertThat(json).contains("\"couplingRatio\":1.0");
        assertThat(json).contains("\"avgCoupling\":1.0");
    }

    @Test
    void globalMode_minClusterSizeFilter_excludesAllClusters() {
        // All clusters have 2 files; requiring 3 excludes them all
        String json = text(tool.handle(Map.of("minClusterSize", 3)));
        assertThat(json).isEqualTo("[]");
    }

    @Test
    void globalMode_highMinCoupling_excludesAll() {
        // coupling_ratio is 1.0; threshold > 1.0 returns nothing
        String json = text(tool.handle(Map.of("minCoupling", 1.1)));
        assertThat(json).isEqualTo("[]");
    }

    // --- Mode 2: single-file lookup ---

    @Test
    void fileMode_returnsCorrectCluster() {
        String json = text(tool.handle(Map.of("filePath", "auth/Login.java")));
        assertThat(json).contains("auth/Login.java");
        assertThat(json).contains("auth/Auth.java");
        // Repo files must NOT appear
        assertThat(json).doesNotContain("UserRepo");
        assertThat(json).contains("\"clusterIndex\":1");
    }

    @Test
    void fileMode_unknownFile_returnsEmptyArray() {
        String json = text(tool.handle(Map.of("filePath", "does/not/exist/Foo.java")));
        assertThat(json).isEqualTo("[]");
    }

    // --- Guard ---

    @Test
    void indexNotReady_returnsError() {
        GetCouplingClustersTool unready =
                new GetCouplingClustersTool(db.attach(FileCouplingDao.class), new IndexTracker());
        CallToolResult result = unready.handle(Map.of());
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("Index not ready");
    }

    // --- sinceEpochMs (CTE path) ---

    @Test
    void globalMode_sinceEpochMs_returnsSameClusters() {
        // epoch 0 includes all commits → same result as fast path
        String json = text(tool.handle(Map.of("sinceEpochMs", 0)));
        assertThat(json).contains("auth/Login.java");
        assertThat(json).contains("repo/UserRepo.java");
    }

    @Test
    void fileMode_sinceEpochMs_returnsCorrectCluster() {
        String json =
                text(tool.handle(Map.of("filePath", "auth/Login.java", "sinceEpochMs", 0)));
        assertThat(json).contains("auth/Login.java");
        assertThat(json).contains("auth/Auth.java");
        assertThat(json).doesNotContain("UserRepo");
    }

    private static String text(CallToolResult r) {
        return ((TextContent) r.content().getFirst()).text();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail (class not found)**

```bash
mvn test -pl . -Dtest=GetCouplingClustersAcceptanceTest -q 2>&1 | tail -5
```

Expected: compilation error — `GetCouplingClustersTool` does not exist yet.

---

## Task 3: DAO Fast-Path Queries + Tool Implementation

**Files:**
- Modify: `src/main/java/de/mfietz/msrmcp/db/FileCouplingDao.java`
- Create: `src/main/java/de/mfietz/msrmcp/tool/GetCouplingClustersTool.java`

- [ ] **Step 1: Add fast-path DAO queries to `FileCouplingDao.java`**

Add after the existing `findTopCoupledForFileSince` method (before `deleteAll`):

```java
    @SqlQuery(
            """
            SELECT
              fa.path AS file_a, fb.path AS file_b,
              fc.co_changes, fc.total_changes_a, fc.total_changes_b,
              CAST(fc.co_changes AS REAL) / MAX(fc.total_changes_a, fc.total_changes_b)
                AS coupling_ratio
            FROM file_coupling fc
            JOIN files fa ON fa.file_id = fc.file_a_id
            JOIN files fb ON fb.file_id = fc.file_b_id
            WHERE fc.total_changes_a > 0 AND fc.total_changes_b > 0
              AND CAST(fc.co_changes AS REAL) / MAX(fc.total_changes_a, fc.total_changes_b)
                >= :minCoupling
            ORDER BY coupling_ratio DESC
            LIMIT 10000
            """)
    List<CouplingRow> findEdgesForClustering(@Bind("minCoupling") double minCoupling);

    @SqlQuery(
            """
            WITH RECURSIVE component(file_id) AS (
              SELECT f.file_id FROM files f WHERE f.path = :filePath
              UNION
              SELECT CASE WHEN fc.file_a_id = c.file_id
                         THEN fc.file_b_id ELSE fc.file_a_id END
              FROM file_coupling fc
              JOIN component c ON fc.file_a_id = c.file_id OR fc.file_b_id = c.file_id
              WHERE CAST(fc.co_changes AS REAL)
                      / MAX(fc.total_changes_a, fc.total_changes_b) >= :minCoupling
            )
            SELECT
              fa.path AS file_a, fb.path AS file_b,
              fc.co_changes, fc.total_changes_a, fc.total_changes_b,
              CAST(fc.co_changes AS REAL) / MAX(fc.total_changes_a, fc.total_changes_b)
                AS coupling_ratio
            FROM file_coupling fc
            JOIN files fa ON fa.file_id = fc.file_a_id
            JOIN files fb ON fb.file_id = fc.file_b_id
            WHERE fc.file_a_id IN (SELECT file_id FROM component)
              AND fc.file_b_id IN (SELECT file_id FROM component)
            ORDER BY coupling_ratio DESC
            """)
    List<CouplingRow> findClusterForFile(
            @Bind("filePath") String filePath, @Bind("minCoupling") double minCoupling);
```

- [ ] **Step 2: Create `GetCouplingClustersTool.java`**

```java
package de.mfietz.msrmcp.tool;

import static de.mfietz.msrmcp.tool.GetHotspotsTool.*;

import de.mfietz.msrmcp.db.FileCouplingDao;
import de.mfietz.msrmcp.db.FileCouplingDao.CouplingRow;
import de.mfietz.msrmcp.index.IndexTracker;
import de.mfietz.msrmcp.model.ClusterEdge;
import de.mfietz.msrmcp.model.CouplingClusterResult;
import io.modelcontextprotocol.spec.McpSchema.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP tool: {@code get_coupling_clusters}
 *
 * <p>Two modes:
 *
 * <ul>
 *   <li>Global scan (no {@code filePath}): Union-Find over all edges ≥ minCoupling from the
 *       pre-aggregated {@code file_coupling} table.
 *   <li>Single-file lookup ({@code filePath} set): recursive CTE traverses only the target file's
 *       connected component — much faster for targeted queries.
 * </ul>
 *
 * <p>Uses MAX normalization ({@code co_changes / MAX(total_a, total_b)}) so hub files that
 * co-change with many unrelated files are excluded from clusters naturally.
 */
public final class GetCouplingClustersTool {

    static final String NAME = "get_coupling_clusters";
    private static final JsonMapper MAPPER = JsonMapper.shared();
    private static final int EDGE_CAP = 10_000;

    private final FileCouplingDao fileCouplingDao;
    private final IndexTracker tracker;

    public GetCouplingClustersTool(FileCouplingDao fileCouplingDao, IndexTracker tracker) {
        this.fileCouplingDao = fileCouplingDao;
        this.tracker = tracker;
    }

    public CallToolResult handle(Map<String, Object> args) {
        if (!tracker.isReady()) {
            return error(
                    "Index not ready (status: "
                            + tracker.state().name().toLowerCase()
                            + "). Call get_index_status to check progress.");
        }
        try {
            double minCoupling = doubleArg(args, "minCoupling", 0.3);
            int minClusterSize = intArg(args, "minClusterSize", 2);
            int topN = intArg(args, "topN", 20);
            Long sinceEpochMs = longArg(args, "sinceEpochMs");
            String filePath = stringArg(args, "filePath", null);

            if (filePath != null && !filePath.isBlank()) {
                return handleFileMode(filePath, minCoupling, sinceEpochMs);
            }
            return handleGlobalMode(minCoupling, minClusterSize, topN, sinceEpochMs);
        } catch (Exception e) {
            return error("get_coupling_clusters failed: " + e.getMessage());
        }
    }

    private CallToolResult handleGlobalMode(
            double minCoupling, int minClusterSize, int topN, Long sinceEpochMs) throws Exception {
        List<CouplingRow> rows =
                sinceEpochMs != null
                        ? fileCouplingDao.findEdgesForClusteringSince(sinceEpochMs, minCoupling)
                        : fileCouplingDao.findEdgesForClustering(minCoupling);

        if (rows.size() == EDGE_CAP) {
            return error(
                    "Edge cap reached (10 000 edges). Increase minCoupling to reduce graph size.");
        }

        UnionFind uf = new UnionFind();
        for (CouplingRow row : rows) {
            uf.union(row.fileA(), row.fileB());
        }

        Map<String, List<CouplingRow>> byRoot = new HashMap<>();
        for (CouplingRow row : rows) {
            byRoot.computeIfAbsent(uf.find(row.fileA()), k -> new ArrayList<>()).add(row);
        }

        List<CouplingClusterResult> clusters = new ArrayList<>();
        for (List<CouplingRow> clusterEdges : byRoot.values()) {
            Set<String> members = new HashSet<>();
            for (CouplingRow e : clusterEdges) {
                members.add(e.fileA());
                members.add(e.fileB());
            }
            if (members.size() < minClusterSize) continue;
            clusters.add(buildCluster(0, clusterEdges));
        }

        clusters.sort(Comparator.comparingDouble(CouplingClusterResult::avgCoupling).reversed());

        List<CouplingClusterResult> result = new ArrayList<>();
        for (int i = 0; i < Math.min(clusters.size(), topN); i++) {
            CouplingClusterResult c = clusters.get(i);
            result.add(new CouplingClusterResult(i + 1, c.files(), c.edges(), c.avgCoupling()));
        }
        return ok(MAPPER.writeValueAsString(result));
    }

    private CallToolResult handleFileMode(
            String filePath, double minCoupling, Long sinceEpochMs) throws Exception {
        List<CouplingRow> rows =
                sinceEpochMs != null
                        ? fileCouplingDao.findClusterForFileSince(filePath, sinceEpochMs, minCoupling)
                        : fileCouplingDao.findClusterForFile(filePath, minCoupling);
        if (rows.isEmpty()) return ok("[]");
        return ok(MAPPER.writeValueAsString(List.of(buildCluster(1, rows))));
    }

    private static CouplingClusterResult buildCluster(int index, List<CouplingRow> edges) {
        Set<String> files = new TreeSet<>();
        List<ClusterEdge> edgeList = new ArrayList<>();
        double sumRatio = 0.0;
        for (CouplingRow row : edges) {
            files.add(row.fileA());
            files.add(row.fileB());
            edgeList.add(
                    new ClusterEdge(row.fileA(), row.fileB(), row.coChanges(), row.couplingRatio()));
            sumRatio += row.couplingRatio();
        }
        double avgCoupling = edges.isEmpty() ? 0.0 : sumRatio / edges.size();
        return new CouplingClusterResult(index, List.copyOf(files), edgeList, avgCoupling);
    }

    static Tool toolSpec() {
        return Tool.builder()
                .name(NAME)
                .description(
                        """
                        Identifies groups of files that frequently change together (co-change clusters).
                        Uses bidirectional coupling (co_changes / MAX(total_a, total_b)) to exclude
                        hub files that are changed alongside everything.
                        Without filePath: returns all clusters sorted by average coupling (global scan).
                        With filePath: returns the single cluster containing that file (fast recursive
                        graph traversal — preferred for targeted lookups).
                        """)
                .inputSchema(ToolSchemas.couplingClusters())
                .build();
    }

    private static final class UnionFind {

        private final Map<String, String> parent = new HashMap<>();

        String find(String x) {
            parent.putIfAbsent(x, x);
            if (!parent.get(x).equals(x)) {
                parent.put(x, find(parent.get(x))); // path compression
            }
            return parent.get(x);
        }

        void union(String x, String y) {
            String px = find(x), py = find(y);
            if (!px.equals(py)) parent.put(px, py);
        }
    }
}
```

Note: `findEdgesForClusteringSince` and `findClusterForFileSince` are referenced here but added in Task 4. The tool will not compile until Task 4 is complete.

- [ ] **Step 3: Add stub DAO signatures (for compilation)**

Add these two stubs to `FileCouplingDao.java` temporarily — they will be replaced with real SQL in Task 4:

```java
    @SqlQuery("SELECT fa.path AS file_a, fb.path AS file_b, fc.co_changes, fc.total_changes_a, fc.total_changes_b, CAST(fc.co_changes AS REAL) / MAX(fc.total_changes_a, fc.total_changes_b) AS coupling_ratio FROM file_coupling fc JOIN files fa ON fa.file_id = fc.file_a_id JOIN files fb ON fb.file_id = fc.file_b_id WHERE 1=0")
    List<CouplingRow> findEdgesForClusteringSince(
            @Bind("sinceEpochMs") Long sinceEpochMs, @Bind("minCoupling") double minCoupling);

    @SqlQuery("SELECT fa.path AS file_a, fb.path AS file_b, fc.co_changes, fc.total_changes_a, fc.total_changes_b, CAST(fc.co_changes AS REAL) / MAX(fc.total_changes_a, fc.total_changes_b) AS coupling_ratio FROM file_coupling fc JOIN files fa ON fa.file_id = fc.file_a_id JOIN files fb ON fb.file_id = fc.file_b_id WHERE 1=0")
    List<CouplingRow> findClusterForFileSince(
            @Bind("filePath") String filePath,
            @Bind("sinceEpochMs") Long sinceEpochMs,
            @Bind("minCoupling") double minCoupling);
```

- [ ] **Step 4: Add `couplingClusters()` stub to `ToolSchemas.java`** (so `GetCouplingClustersTool` compiles)

Add at the end of `ToolSchemas.java` before the closing `}`:

```java
    static JsonSchema couplingClusters() {
        return new JsonSchema(
                "object",
                Map.of(
                        "filePath",
                                Map.of(
                                        "type",
                                        "string",
                                        "description",
                                        "Repo-relative path, e.g. \"src/Main.java\". If set, returns only"
                                                + " the cluster containing this file (fast recursive"
                                                + " lookup). If absent, returns all clusters (global scan)."),
                        "minCoupling",
                                Map.of(
                                        "type",
                                        "number",
                                        "description",
                                        "Minimum bidirectional coupling ratio 0–1 (default 0.3)."
                                                + " Uses co_changes / MAX(total_a, total_b) —"
                                                + " higher values yield tighter, smaller clusters.",
                                        "minimum",
                                        0.0,
                                        "maximum",
                                        1.0,
                                        "default",
                                        0.3),
                        "minClusterSize",
                                Map.of(
                                        "type",
                                        "integer",
                                        "description",
                                        "Minimum files per cluster (default 2, global mode only)",
                                        "minimum",
                                        2,
                                        "default",
                                        2),
                        "topN",
                                Map.of(
                                        "type",
                                        "integer",
                                        "description",
                                        "Max clusters to return (default 20, global mode only)",
                                        "minimum",
                                        1,
                                        "default",
                                        20),
                        "sinceEpochMs",
                                Map.of(
                                        "type",
                                        "integer",
                                        "description",
                                        "Time window start (ms); triggers dynamic query when set")),
                List.of(),
                null,
                null,
                null);
    }
```

- [ ] **Step 5: Verify compilation**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Run non-since tests only**

```bash
mvn test -Dtest=GetCouplingClustersAcceptanceTest#globalMode_returnsTwoClusters+globalMode_eachClusterHasEdgesAndFullCoupling+globalMode_minClusterSizeFilter_excludesAllClusters+globalMode_highMinCoupling_excludesAll+fileMode_returnsCorrectCluster+fileMode_unknownFile_returnsEmptyArray+indexNotReady_returnsError -q 2>&1 | tail -10
```

Expected: 7 tests pass. The `sinceEpochMs` tests will fail (stubs return nothing) — that's OK, addressed in Task 4.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/de/mfietz/msrmcp/db/FileCouplingDao.java \
        src/main/java/de/mfietz/msrmcp/tool/GetCouplingClustersTool.java \
        src/main/java/de/mfietz/msrmcp/tool/ToolSchemas.java \
        src/test/java/de/mfietz/msrmcp/acceptance/GetCouplingClustersAcceptanceTest.java
git commit -m "feat: add get_coupling_clusters tool (fast-path, no sinceEpochMs yet)"
```

---

## Task 4: DAO Since-Path Queries

**Files:**
- Modify: `src/main/java/de/mfietz/msrmcp/db/FileCouplingDao.java`

Replace the two stubs added in Task 3 Step 3 with the real SQL.

- [ ] **Step 1: Replace `findEdgesForClusteringSince` stub with real query**

Replace the one-liner stub with:

```java
    @SqlQuery(
            """
            WITH recent AS (
              SELECT fc.file_id, fc.commit_id
              FROM file_changes fc
              JOIN commits c ON c.commit_id = fc.commit_id
              WHERE c.author_date >= :sinceEpochMs
            ),
            totals AS (
              SELECT file_id, COUNT(DISTINCT commit_id) AS total_changes
              FROM recent
              GROUP BY file_id
            )
            SELECT
              fa.path AS file_a,
              fb.path AS file_b,
              COUNT(DISTINCT a.commit_id) AS co_changes,
              ta.total_changes AS total_changes_a,
              tb.total_changes AS total_changes_b,
              CAST(COUNT(DISTINCT a.commit_id) AS REAL) / MAX(ta.total_changes, tb.total_changes)
                AS coupling_ratio
            FROM recent a
            JOIN recent b ON b.commit_id = a.commit_id AND a.file_id < b.file_id
            JOIN totals ta ON ta.file_id = a.file_id
            JOIN totals tb ON tb.file_id = b.file_id
            JOIN files fa ON fa.file_id = a.file_id
            JOIN files fb ON fb.file_id = b.file_id
            GROUP BY a.file_id, b.file_id
            HAVING coupling_ratio >= :minCoupling
            ORDER BY coupling_ratio DESC
            LIMIT 10000
            """)
    List<CouplingRow> findEdgesForClusteringSince(
            @Bind("sinceEpochMs") Long sinceEpochMs, @Bind("minCoupling") double minCoupling);
```

- [ ] **Step 2: Replace `findClusterForFileSince` stub with real query**

```java
    @SqlQuery(
            """
            WITH RECURSIVE recent AS (
              SELECT fc.file_id, fc.commit_id
              FROM file_changes fc
              JOIN commits c ON c.commit_id = fc.commit_id
              WHERE c.author_date >= :sinceEpochMs
            ),
            totals AS (
              SELECT file_id, COUNT(DISTINCT commit_id) AS total_changes
              FROM recent
              GROUP BY file_id
            ),
            coupling_since AS (
              SELECT
                a.file_id AS file_a_id,
                b.file_id AS file_b_id,
                COUNT(DISTINCT a.commit_id) AS co_changes,
                ta.total_changes AS total_changes_a,
                tb.total_changes AS total_changes_b,
                CAST(COUNT(DISTINCT a.commit_id) AS REAL) / MAX(ta.total_changes, tb.total_changes)
                  AS coupling_ratio
              FROM recent a
              JOIN recent b ON b.commit_id = a.commit_id AND a.file_id < b.file_id
              JOIN totals ta ON ta.file_id = a.file_id
              JOIN totals tb ON tb.file_id = b.file_id
              GROUP BY a.file_id, b.file_id
              HAVING coupling_ratio >= :minCoupling
            ),
            component(file_id) AS (
              SELECT f.file_id FROM files f WHERE f.path = :filePath
              UNION
              SELECT CASE WHEN cs.file_a_id = c.file_id
                         THEN cs.file_b_id ELSE cs.file_a_id END
              FROM coupling_since cs
              JOIN component c ON cs.file_a_id = c.file_id OR cs.file_b_id = c.file_id
            )
            SELECT
              fa.path AS file_a,
              fb.path AS file_b,
              cs.co_changes,
              cs.total_changes_a,
              cs.total_changes_b,
              cs.coupling_ratio
            FROM coupling_since cs
            JOIN files fa ON fa.file_id = cs.file_a_id
            JOIN files fb ON fb.file_id = cs.file_b_id
            WHERE cs.file_a_id IN (SELECT file_id FROM component)
              AND cs.file_b_id IN (SELECT file_id FROM component)
            ORDER BY cs.coupling_ratio DESC
            """)
    List<CouplingRow> findClusterForFileSince(
            @Bind("filePath") String filePath,
            @Bind("sinceEpochMs") Long sinceEpochMs,
            @Bind("minCoupling") double minCoupling);
```

- [ ] **Step 3: Run the full acceptance test**

```bash
mvn test -Dtest=GetCouplingClustersAcceptanceTest -q 2>&1 | tail -10
```

Expected: all 9 tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/de/mfietz/msrmcp/db/FileCouplingDao.java
git commit -m "feat: add sinceEpochMs DAO queries for coupling cluster detection"
```

---

## Task 5: Wire into ToolRegistry

**Files:**
- Modify: `src/main/java/de/mfietz/msrmcp/tool/ToolRegistry.java`

- [ ] **Step 1: Instantiate and register the tool**

In `ToolRegistry.buildSpecs`, add after the `GetStaleFilesTool` line:

```java
        GetCouplingClustersTool couplingClusters =
                new GetCouplingClustersTool(fileCouplingDao, tracker);
```

And add to the `List.of(...)`:

```java
                new McpServerFeatures.SyncToolSpecification(
                        GetCouplingClustersTool.toolSpec(),
                        (exchange, req) -> couplingClusters.handle(req.arguments())),
```

- [ ] **Step 2: Run full test suite**

```bash
mvn verify -q 2>&1 | tail -15
```

Expected: BUILD SUCCESS, all tests pass (including pre-existing acceptance tests).

- [ ] **Step 3: Run Spotless**

```bash
mvn spotless:apply
```

- [ ] **Step 4: Verify formatting is clean**

```bash
mvn spotless:check -q
```

Expected: BUILD SUCCESS (no formatting violations).

- [ ] **Step 5: Final test run after formatting**

```bash
mvn verify -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/de/mfietz/msrmcp/tool/ToolRegistry.java \
        src/main/java/de/mfietz/msrmcp/tool/ToolSchemas.java \
        src/main/java/de/mfietz/msrmcp/tool/GetCouplingClustersTool.java \
        src/main/java/de/mfietz/msrmcp/db/FileCouplingDao.java \
        src/main/java/de/mfietz/msrmcp/model/ClusterEdge.java \
        src/main/java/de/mfietz/msrmcp/model/CouplingClusterResult.java \
        src/test/java/de/mfietz/msrmcp/acceptance/GetCouplingClustersAcceptanceTest.java
git commit -m "feat: register get_coupling_clusters in ToolRegistry"
```
