package de.mfietz.msrmcp.db;

import de.mfietz.msrmcp.model.CommitRecord;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

public interface CommitDao {

    @SqlUpdate(
            """
            INSERT OR IGNORE INTO commits(hash, author_date, first_line, jira_slug, author_email, author_name)
            VALUES(:hash, :authorDate, :firstLine, :jiraSlug, :authorEmail, :authorName)
            """)
    void insert(@BindMethods CommitRecord c);

    @SqlBatch(
            """
            INSERT OR IGNORE INTO commits(hash, author_date, first_line, jira_slug, author_email, author_name)
            VALUES(:hash, :authorDate, :firstLine, :jiraSlug, :authorEmail, :authorName)
            """)
    void insertBatch(@BindMethods List<CommitRecord> commits);

    @SqlQuery("SELECT hash FROM commits ORDER BY author_date DESC LIMIT 1")
    Optional<String> findLatestHash();

    @SqlQuery(
            "SELECT hash, author_date, first_line, jira_slug, author_email, author_name FROM commits WHERE hash = :hash")
    Optional<CommitRecord> findByHash(@Bind("hash") String hash);

    @SqlQuery("SELECT COUNT(*) FROM commits")
    int count();

    @SqlQuery("SELECT COUNT(DISTINCT author_email) FROM commits")
    int countDistinctAuthors();

    @SqlQuery(
            """
            SELECT author_email, author_name, COUNT(*) AS commit_count
            FROM commits
            GROUP BY author_email
            ORDER BY commit_count DESC
            LIMIT :topN
            """)
    List<AuthorRow> findTopAuthors(@Bind("topN") int topN);

    @SqlQuery("SELECT MIN(author_date) FROM commits")
    Optional<Long> findEarliestAuthorDate();

    @SqlQuery("SELECT MAX(author_date) FROM commits")
    Optional<Long> findLatestAuthorDate();

    @SqlQuery("SELECT commit_id, hash FROM commits WHERE hash IN (<hashes>)")
    List<CommitIdRecord> findByHashes(@BindList("hashes") List<String> hashes);

    @SqlQuery(
            """
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

    @SqlQuery(
            """
            WITH file_author_counts AS (
              SELECT fc.file_id, c.author_email, c.author_name, COUNT(*) AS author_commits
              FROM file_changes fc
              JOIN commits c ON c.commit_id = fc.commit_id
              WHERE (:sinceEpochMs IS NULL OR c.author_date >= :sinceEpochMs)
              GROUP BY fc.file_id, c.author_email
            ),
            file_totals AS (
              SELECT file_id,
                     SUM(author_commits) AS total_commits,
                     MAX(author_commits) AS max_author_commits
              FROM file_author_counts
              GROUP BY file_id
            )
            SELECT
              f.path AS file_path,
              fac.author_email AS top_author_email,
              fac.author_name  AS top_author_name,
              fac.author_commits AS top_author_commits,
              ft.total_commits,
              CAST(fac.author_commits AS REAL) / ft.total_commits AS dominance_ratio
            FROM file_totals ft
            JOIN file_author_counts fac ON fac.file_id = ft.file_id
                                       AND fac.author_commits = ft.max_author_commits
            JOIN files f ON f.file_id = ft.file_id
            WHERE CAST(ft.max_author_commits AS REAL) / ft.total_commits >= :threshold
              AND (:pathFilter IS NULL OR f.path LIKE :pathFilter)
            ORDER BY dominance_ratio DESC
            LIMIT :topN
            """)
    List<BusFactorRow> findBusFactorFiles(
            @Bind("sinceEpochMs") Long sinceEpochMs,
            @Bind("threshold") double threshold,
            @Bind("pathFilter") String pathFilter,
            @Bind("topN") int topN);

    @SqlQuery(
            """
            WITH file_author_counts AS (
              SELECT fc.file_id, c.author_email, c.author_name, COUNT(*) AS author_amount
              FROM file_changes fc
              JOIN commits c ON c.commit_id = fc.commit_id
              WHERE (:sinceEpochMs IS NULL OR c.author_date >= :sinceEpochMs)
              GROUP BY fc.file_id, c.author_email
            ),
            file_totals AS (
              SELECT file_id, SUM(author_amount) AS total_amount, MAX(author_amount) AS max_author_amount
              FROM file_author_counts GROUP BY file_id
            )
            SELECT f.path AS file_path,
                   fac.author_email AS top_author_email, fac.author_name AS top_author_name,
                   fac.author_amount AS top_author_amount, ft.total_amount,
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

    @SqlQuery(
            """
            WITH file_author_lines AS (
              SELECT fc.file_id, c.author_email, c.author_name, SUM(fc.lines_added) AS author_amount
              FROM file_changes fc
              JOIN commits c ON c.commit_id = fc.commit_id
              WHERE (:sinceEpochMs IS NULL OR c.author_date >= :sinceEpochMs)
              GROUP BY fc.file_id, c.author_email
            ),
            file_totals AS (
              SELECT file_id, SUM(author_amount) AS total_amount, MAX(author_amount) AS max_author_amount
              FROM file_author_lines GROUP BY file_id
              HAVING SUM(author_amount) > 0
            )
            SELECT f.path AS file_path,
                   fal.author_email AS top_author_email, fal.author_name AS top_author_name,
                   fal.author_amount AS top_author_amount, ft.total_amount,
                   CAST(fal.author_amount AS REAL) / ft.total_amount AS ownership_ratio
            FROM file_totals ft
            JOIN file_author_lines fal ON fal.file_id = ft.file_id
                                       AND fal.author_amount = ft.max_author_amount
            JOIN files f ON f.file_id = ft.file_id
            WHERE f.path LIKE :extensionPattern
              AND f.path LIKE :pathFilter
              AND CAST(fal.author_amount AS REAL) / ft.total_amount >= :minOwnership
            ORDER BY ownership_ratio DESC
            LIMIT :topN
            """)
    List<OwnershipRow> findOwnershipByLines(
            @Bind("sinceEpochMs") Long sinceEpochMs,
            @Bind("extensionPattern") String extensionPattern,
            @Bind("pathFilter") String pathFilter,
            @Bind("minOwnership") double minOwnership,
            @Bind("topN") int topN);

    record CommitIdRecord(long commitId, String hash) {}

    record AuthorRow(String authorEmail, String authorName, int commitCount) {}

    record BusFactorRow(
            String filePath,
            String topAuthorEmail,
            String topAuthorName,
            int topAuthorCommits,
            int totalCommits,
            double dominanceRatio) {}

    record OwnershipRow(
            String filePath,
            String topAuthorEmail,
            String topAuthorName,
            long topAuthorAmount,
            long totalAmount,
            double ownershipRatio) {}
}
