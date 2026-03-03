package com.example.msrmcp.db;

import com.example.msrmcp.model.FileChangeRecord;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

import java.util.List;

public interface FileChangeDao {

    @SqlBatch("""
            INSERT INTO file_changes(commit_hash, file_path)
            VALUES(:commitHash, :filePath)
            """)
    void insertBatch(@BindMethods List<FileChangeRecord> changes);

    /**
     * Top changed files, optionally filtered by epoch and file extension glob.
     * sinceEpochMs=null means all time. extensionPattern e.g. "%.java".
     */
    @SqlQuery("""
            SELECT fc.file_path, COUNT(*) AS change_frequency
            FROM file_changes fc
            JOIN commits c ON c.hash = fc.commit_hash
            WHERE (:sinceEpochMs IS NULL OR c.author_date >= :sinceEpochMs)
              AND fc.file_path LIKE :extensionPattern
            GROUP BY fc.file_path
            ORDER BY change_frequency DESC
            LIMIT :topN
            """)
    List<FileChangeFrequencyRow> findTopChangedFiles(
            @Bind("sinceEpochMs") Long sinceEpochMs,
            @Bind("extensionPattern") String extensionPattern,
            @Bind("topN") int topN);

    @SqlQuery("""
            SELECT fc.commit_hash
            FROM file_changes fc
            JOIN commits c ON c.hash = fc.commit_hash
            WHERE fc.file_path = :filePath
              AND (:sinceEpochMs IS NULL OR c.author_date >= :sinceEpochMs)
            ORDER BY c.author_date DESC
            LIMIT :limit
            """)
    List<String> findCommitHashesForFile(
            @Bind("filePath") String filePath,
            @Bind("sinceEpochMs") Long sinceEpochMs,
            @Bind("limit") int limit);

    @SqlQuery("SELECT file_path FROM file_changes WHERE commit_hash = :commitHash")
    List<String> findPathsByCommit(@Bind("commitHash") String commitHash);

    record FileChangeFrequencyRow(String filePath, int changeFrequency) {}
}
