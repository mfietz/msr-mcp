package com.example.msrmcp.db;

import com.example.msrmcp.model.CommitRecord;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;

public interface CommitDao {

    @SqlUpdate("""
            INSERT OR IGNORE INTO commits(hash, author_date, first_line, jira_slug, author_email, author_name)
            VALUES(:hash, :authorDate, :firstLine, :jiraSlug, :authorEmail, :authorName)
            """)
    void insert(@BindMethods CommitRecord c);

    @SqlBatch("""
            INSERT OR IGNORE INTO commits(hash, author_date, first_line, jira_slug, author_email, author_name)
            VALUES(:hash, :authorDate, :firstLine, :jiraSlug, :authorEmail, :authorName)
            """)
    void insertBatch(@BindMethods List<CommitRecord> commits);

    @SqlQuery("SELECT hash FROM commits ORDER BY author_date DESC LIMIT 1")
    Optional<String> findLatestHash();

    @SqlQuery("SELECT hash, author_date, first_line, jira_slug, author_email, author_name FROM commits WHERE hash = :hash")
    Optional<CommitRecord> findByHash(@Bind("hash") String hash);

    @SqlQuery("SELECT COUNT(*) FROM commits")
    int count();

    @SqlQuery("SELECT MIN(author_date) FROM commits")
    Optional<Long> findEarliestAuthorDate();

    @SqlQuery("SELECT MAX(author_date) FROM commits")
    Optional<Long> findLatestAuthorDate();

    @SqlQuery("SELECT commit_id, hash FROM commits WHERE hash IN (<hashes>)")
    List<CommitIdRecord> findByHashes(@BindList("hashes") List<String> hashes);

    @SqlQuery("""
            SELECT c.author_email, c.author_name, COUNT(*) AS commit_count
            FROM file_changes fc
            JOIN commits c ON c.commit_id = fc.commit_id
            WHERE fc.file_id = (SELECT file_id FROM files WHERE path = :filePath)
              AND (:sinceEpochMs IS NULL OR c.author_date >= :sinceEpochMs)
            GROUP BY c.author_email
            ORDER BY commit_count DESC
            LIMIT :topN
            """)
    List<AuthorRow> findAuthorsForFile(
            @Bind("filePath") String filePath,
            @Bind("sinceEpochMs") Long sinceEpochMs,
            @Bind("topN") int topN);

    record CommitIdRecord(long commitId, String hash) {}

    record AuthorRow(String authorEmail, String authorName, int commitCount) {}
}
