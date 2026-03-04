package com.example.msrmcp.db;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

import java.util.List;

public interface FileChangeDao {

    @SqlBatch("""
            INSERT INTO file_changes(commit_hash, file_id)
            VALUES(:commitHash, :fileId)
            """)
    void insertBatch(@BindMethods List<FileChangeIdRecord> changes);

    @SqlQuery("""
            SELECT f.path AS file_path, COUNT(*) AS change_frequency
            FROM file_changes fc
            JOIN files f ON f.file_id = fc.file_id
            JOIN commits c ON c.hash = fc.commit_hash
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
            SELECT fc.commit_hash
            FROM file_changes fc
            JOIN commits c ON c.hash = fc.commit_hash
            WHERE fc.file_id = (SELECT file_id FROM files WHERE path = :filePath)
              AND (:sinceEpochMs IS NULL OR c.author_date >= :sinceEpochMs)
            ORDER BY c.author_date DESC
            LIMIT :limit
            """)
    List<String> findCommitHashesForFile(
            @Bind("filePath") String filePath,
            @Bind("sinceEpochMs") Long sinceEpochMs,
            @Bind("limit") int limit);

    @SqlQuery("""
            SELECT f.path
            FROM file_changes fc
            JOIN files f ON f.file_id = fc.file_id
            WHERE fc.commit_hash = :commitHash
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

    record FileChangeIdRecord(String commitHash, long fileId) {}

    record FileChangeFrequencyRow(String filePath, int changeFrequency) {}
}
