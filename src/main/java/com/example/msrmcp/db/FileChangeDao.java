package com.example.msrmcp.db;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;

public interface FileChangeDao {

    @SqlBatch("""
            INSERT INTO file_changes(commit_id, file_id, lines_added, lines_deleted)
            VALUES(:commitId, :fileId, :linesAdded, :linesDeleted)
            """)
    void insertBatch(@BindMethods List<FileChangeIdRecord> changes);

    @SqlQuery("""
            SELECT f.path AS file_path,
                   COUNT(*) AS change_frequency,
                   age.first_commit_ms,
                   age.last_commit_ms
            FROM file_changes fc
            JOIN files f ON f.file_id = fc.file_id
            JOIN commits c ON c.commit_id = fc.commit_id
            JOIN (
                SELECT fc2.file_id,
                       MIN(c2.author_date) AS first_commit_ms,
                       MAX(c2.author_date) AS last_commit_ms
                FROM file_changes fc2
                JOIN commits c2 ON c2.commit_id = fc2.commit_id
                GROUP BY fc2.file_id
            ) age ON age.file_id = fc.file_id
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

    @SqlQuery("""
            SELECT c.hash AS commit_hash
            FROM file_changes fc
            JOIN commits c ON c.commit_id = fc.commit_id
            WHERE fc.file_id = (SELECT file_id FROM files WHERE path = :filePath)
              AND (:sinceEpochMs IS NULL OR c.author_date >= :sinceEpochMs)
              AND (:jiraSlug IS NULL OR c.jira_slug LIKE :jiraSlug)
            ORDER BY c.author_date DESC
            LIMIT :limit
            """)
    List<String> findCommitHashesForFile(
            @Bind("filePath") String filePath,
            @Bind("sinceEpochMs") Long sinceEpochMs,
            @Bind("jiraSlug") String jiraSlug,
            @Bind("limit") int limit);

    @SqlQuery("""
            SELECT f.path
            FROM file_changes fc
            JOIN files f ON f.file_id = fc.file_id
            WHERE fc.commit_id = (SELECT commit_id FROM commits WHERE hash = :commitHash)
            """)
    List<String> findPathsByCommit(@Bind("commitHash") String commitHash);

    @SqlQuery("""
            SELECT DISTINCT f.path
            FROM file_changes fc
            JOIN files f ON f.file_id = fc.file_id
            """)
    List<String> findDistinctPaths();

    @SqlQuery("SELECT COUNT(DISTINCT file_id) FROM file_changes")
    int countDistinctPaths();

    @SqlUpdate("UPDATE file_changes SET file_id = :newId WHERE file_id = :oldId")
    void updateFileId(@Bind("oldId") long oldId, @Bind("newId") long newId);

    @SqlQuery("""
            SELECT f.path AS file_path,
                   SUM(fc.lines_added)   AS lines_added,
                   SUM(fc.lines_deleted) AS lines_deleted,
                   SUM(fc.lines_added + fc.lines_deleted) AS churn,
                   COUNT(DISTINCT fc.commit_id) AS change_frequency
            FROM file_changes fc
            JOIN files f   ON f.file_id    = fc.file_id
            JOIN commits c ON c.commit_id  = fc.commit_id
            WHERE (:sinceEpochMs IS NULL OR c.author_date >= :sinceEpochMs)
              AND f.path LIKE :extensionPattern
              AND f.path LIKE :pathFilter
            GROUP BY fc.file_id
            HAVING churn > 0
            ORDER BY churn DESC
            LIMIT :topN
            """)
    List<ChurnRow> findTopChurn(
            @Bind("sinceEpochMs") Long sinceEpochMs,
            @Bind("extensionPattern") String extensionPattern,
            @Bind("pathFilter") String pathFilter,
            @Bind("topN") int topN);

    record FileChangeIdRecord(long commitId, long fileId, int linesAdded, int linesDeleted) {}

    record ChurnRow(String filePath, long linesAdded, long linesDeleted, long churn, int changeFrequency) {}

    record FileChangeFrequencyRow(String filePath, int changeFrequency,
                                  long firstCommitMs, long lastCommitMs) {}
}
