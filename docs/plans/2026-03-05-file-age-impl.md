# file_age: extend get_hotspots Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add `ageInDays` and `daysSinceLastChange` fields to `HotspotResult` by extending the existing `findTopChangedFiles` SQL query.

**Architecture:** `FileChangeFrequencyRow` gets raw epoch-ms timestamps from a MIN/MAX SQL aggregation. `HotspotScorer.score()` converts them to integer day counts when building `HotspotResult` entries. No new tool, no new table, no schema migration.

**Tech Stack:** Java records, JDBI ConstructorMapper (auto snake_case→camelCase), SQLite MIN/MAX aggregation.

---

### Task 1: Write the failing test (RED)

**Files:**
- Modify: `src/test/java/com/example/msrmcp/acceptance/GetHotspotsAcceptanceTest.java`

Add a new test method to the existing class (after `topN_limitsResultCount`):

**Step 1: Add test method**

```java
@Test
void hotspotResult_containsAgeFields() {
    CallToolResult result = hotspotsTool.handle(Map.of("topN", 1));
    String json = ((TextContent) result.content().getFirst()).text();

    // Both fields must be present
    assertThat(json).contains("\"ageInDays\"");
    assertThat(json).contains("\"daysSinceLastChange\"");

    // Test commits are from 2024; running in 2026 → at least 300 days old
    assertThat(json).doesNotContain("\"ageInDays\":0");
    assertThat(json).doesNotContain("\"daysSinceLastChange\":0");
}
```

**Step 2: Run to verify it fails**

```bash
mvn test -Dtest="GetHotspotsAcceptanceTest#hotspotResult_containsAgeFields" 2>&1 | tail -10
```

Expected: FAIL — `"ageInDays"` not found in JSON.

---

### Task 2: Extend FileChangeFrequencyRow and SQL query

**Files:**
- Modify: `src/main/java/com/example/msrmcp/db/FileChangeDao.java`

**Step 1: Add fields to FileChangeFrequencyRow** (line 101 — replace the record)

```java
record FileChangeFrequencyRow(String filePath, int changeFrequency,
                              long firstCommitMs, long lastCommitMs) {}
```

**Step 2: Extend findTopChangedFiles SQL** (replace the `@SqlQuery` for `findTopChangedFiles`)

```java
@SqlQuery("""
        SELECT f.path AS file_path,
               COUNT(*) AS change_frequency,
               MIN(c.author_date) AS first_commit_ms,
               MAX(c.author_date) AS last_commit_ms
        FROM file_changes fc
        JOIN files f ON f.file_id = fc.file_id
        JOIN commits c ON c.commit_id = fc.commit_id
        WHERE (:sinceEpochMs IS NULL OR c.author_date >= :sinceEpochMs)
          AND f.path LIKE :extensionPattern
          AND f.path LIKE :pathFilter
        GROUP BY fc.file_id
        ORDER BY change_frequency DESC
        LIMIT :topN
        """)
List<FileChangeFrequencyRow> findTopChangedFiles(
        @Bind("sinceEpochMs") Long sinceEpochMs,
        @Bind("extensionPattern") String extensionPattern,
        @Bind("pathFilter") String pathFilter,
        @Bind("topN") int topN);
```

Note: `Database.java` already registers `ConstructorMapper.factory(FileChangeDao.FileChangeFrequencyRow.class)`. JDBI maps `first_commit_ms` → `firstCommitMs` automatically.

**Step 3: Compile to check no errors**

```bash
mvn compile 2>&1 | tail -15
```

Expected: BUILD SUCCESS (HotspotScorer will have a compile error from the old `HotspotResult` — fix in next task).

---

### Task 3: Extend HotspotResult and HotspotScorer

**Files:**
- Modify: `src/main/java/com/example/msrmcp/model/HotspotResult.java`
- Modify: `src/main/java/com/example/msrmcp/util/HotspotScorer.java`

**Step 1: Add fields to HotspotResult** (replace entire file)

```java
package com.example.msrmcp.model;

public record HotspotResult(
        String path,
        int changeFrequency,
        int linesOfCode,
        int cyclomaticComplexity,
        int cognitiveComplexity,
        double hotspotScore,
        int ageInDays,
        int daysSinceLastChange) {}
```

**Step 2: Update HotspotScorer.score()** — add `now` computation at the top of the method and pass age fields to the constructor.

At the start of `score()` (after `if (candidates.isEmpty()) return List.of();`), add:

```java
long now = System.currentTimeMillis();
```

Replace the `results.add(...)` line inside the for-loop:

```java
int ageInDays          = (int) ((now - row.firstCommitMs())  / 86_400_000L);
int daysSinceLastChange = (int) ((now - row.lastCommitMs())   / 86_400_000L);
results.add(new HotspotResult(row.filePath(), row.changeFrequency(),
        loc, cyclo, cogni, score, ageInDays, daysSinceLastChange));
```

**Step 3: Compile**

```bash
mvn compile 2>&1 | tail -10
```

Expected: BUILD SUCCESS.

---

### Task 4: Verify GREEN

**Step 1: Run the new test**

```bash
mvn test -Dtest="GetHotspotsAcceptanceTest#hotspotResult_containsAgeFields" 2>&1 | tail -10
```

Expected: PASS.

**Step 2: Run full suite**

```bash
mvn test 2>&1 | tail -10
```

Expected: all tests green, no failures.

---

### Task 5: Update docs and commit

**Files:**
- Modify: `CLAUDE.md`

**Step 1: Add to the "model/" section of the package map in CLAUDE.md**

In the model records line, append:
```
# HotspotResult: now also includes ageInDays, daysSinceLastChange (computed from firstCommit/lastCommit epoch-ms)
```

Specifically, update the `HotspotResult` entry in the model description to mention the new fields.

**Step 2: Commit**

```bash
git add src/main/java/com/example/msrmcp/db/FileChangeDao.java \
        src/main/java/com/example/msrmcp/model/HotspotResult.java \
        src/main/java/com/example/msrmcp/util/HotspotScorer.java \
        src/test/java/com/example/msrmcp/acceptance/GetHotspotsAcceptanceTest.java \
        CLAUDE.md
git commit -m "feat: add ageInDays and daysSinceLastChange to get_hotspots results"
```

**Step 3: Push**

```bash
git push
```
