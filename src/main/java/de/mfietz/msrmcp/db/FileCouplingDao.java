package de.mfietz.msrmcp.db;

import java.util.List;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

public interface FileCouplingDao {

    @SqlBatch(
            """
            INSERT INTO file_coupling(file_a_id, file_b_id, co_changes, total_changes_a, total_changes_b)
            VALUES(:fileAId, :fileBId, :coChanges, :totalChangesA, :totalChangesB)
            ON CONFLICT(file_a_id, file_b_id) DO UPDATE SET
              co_changes      = co_changes      + excluded.co_changes,
              total_changes_a = total_changes_a + excluded.total_changes_a,
              total_changes_b = total_changes_b + excluded.total_changes_b
            """)
    void upsertBatch(@BindMethods List<FileCouplingIdRecord> records);

    @SqlQuery(
            """
            SELECT
              fa.path AS file_a, fb.path AS file_b,
              fc.co_changes, fc.total_changes_a, fc.total_changes_b,
              CAST(fc.co_changes AS REAL) / MIN(fc.total_changes_a, fc.total_changes_b) AS coupling_ratio
            FROM file_coupling fc
            JOIN files fa ON fa.file_id = fc.file_a_id
            JOIN files fb ON fb.file_id = fc.file_b_id
            WHERE (:fileFilter IS NULL OR fa.path LIKE :fileFilter OR fb.path LIKE :fileFilter)
              AND fc.total_changes_a > 0 AND fc.total_changes_b > 0
              AND CAST(fc.co_changes AS REAL) / MIN(fc.total_changes_a, fc.total_changes_b) >= :minCoupling
            ORDER BY coupling_ratio DESC
            LIMIT :topN
            """)
    List<CouplingRow> findTopCoupled(
            @Bind("minCoupling") double minCoupling,
            @Bind("fileFilter") String fileFilter,
            @Bind("topN") int topN);

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
              CAST(COUNT(DISTINCT a.commit_id) AS REAL) / MIN(ta.total_changes, tb.total_changes) AS coupling_ratio
            FROM recent a
            JOIN recent b ON b.commit_id = a.commit_id AND a.file_id < b.file_id
            JOIN totals ta ON ta.file_id = a.file_id
            JOIN totals tb ON tb.file_id = b.file_id
            JOIN files fa ON fa.file_id = a.file_id
            JOIN files fb ON fb.file_id = b.file_id
            WHERE (:fileFilter IS NULL OR fa.path LIKE :fileFilter OR fb.path LIKE :fileFilter)
            GROUP BY a.file_id, b.file_id
            HAVING coupling_ratio >= :minCoupling
            ORDER BY coupling_ratio DESC
            LIMIT :topN
            """)
    List<CouplingRow> findTopCoupledSince(
            @Bind("sinceEpochMs") Long sinceEpochMs,
            @Bind("fileFilter") String fileFilter,
            @Bind("minCoupling") double minCoupling,
            @Bind("topN") int topN);

    @SqlQuery(
            """
            SELECT
              CASE WHEN fc.file_a_id = fTarget.file_id THEN fb.path ELSE fa.path END AS partner_path,
              fc.co_changes,
              CASE WHEN fc.file_a_id = fTarget.file_id THEN fc.total_changes_a ELSE fc.total_changes_b END AS target_total_changes,
              CASE WHEN fc.file_a_id = fTarget.file_id THEN fc.total_changes_b ELSE fc.total_changes_a END AS partner_total_changes,
              CAST(fc.co_changes AS REAL) / MIN(fc.total_changes_a, fc.total_changes_b) AS coupling_ratio
            FROM file_coupling fc
            JOIN files fa ON fa.file_id = fc.file_a_id
            JOIN files fb ON fb.file_id = fc.file_b_id
            JOIN files fTarget ON fTarget.path = :filePath
            WHERE (fc.file_a_id = fTarget.file_id OR fc.file_b_id = fTarget.file_id)
              AND CAST(fc.co_changes AS REAL) / MIN(fc.total_changes_a, fc.total_changes_b) >= :minCoupling
            ORDER BY coupling_ratio DESC
            LIMIT :topN
            """)
    List<PartnerRow> findTopCoupledForFile(
            @Bind("filePath") String filePath,
            @Bind("minCoupling") double minCoupling,
            @Bind("topN") int topN);

    @SqlQuery(
            """
            WITH recent AS (
              SELECT fc.file_id, fc.commit_id
              FROM file_changes fc
              JOIN commits c ON c.commit_id = fc.commit_id
              WHERE c.author_date >= :sinceEpochMs
            ),
            target_commits AS (
              SELECT r.commit_id
              FROM recent r
              JOIN files f ON f.file_id = r.file_id AND f.path = :filePath
            ),
            partner_totals AS (
              SELECT file_id, COUNT(DISTINCT commit_id) AS total_changes
              FROM recent
              GROUP BY file_id
            ),
            target_total AS (
              SELECT COUNT(DISTINCT commit_id) AS total_changes FROM target_commits
            )
            SELECT
              fb.path AS partner_path,
              COUNT(DISTINCT r.commit_id) AS co_changes,
              tt.total_changes AS target_total_changes,
              pt.total_changes AS partner_total_changes,
              CAST(COUNT(DISTINCT r.commit_id) AS REAL) / MIN(tt.total_changes, pt.total_changes) AS coupling_ratio
            FROM recent r
            JOIN target_commits tc ON tc.commit_id = r.commit_id
            JOIN files fb ON fb.file_id = r.file_id AND fb.path != :filePath
            JOIN partner_totals pt ON pt.file_id = r.file_id
            CROSS JOIN target_total tt
            GROUP BY r.file_id
            HAVING coupling_ratio >= :minCoupling
            ORDER BY coupling_ratio DESC
            LIMIT :topN
            """)
    List<PartnerRow> findTopCoupledForFileSince(
            @Bind("filePath") String filePath,
            @Bind("sinceEpochMs") Long sinceEpochMs,
            @Bind("minCoupling") double minCoupling,
            @Bind("topN") int topN);

    @SqlUpdate("DELETE FROM file_coupling")
    void deleteAll();

    record FileCouplingIdRecord(
            long fileAId, long fileBId, int coChanges, int totalChangesA, int totalChangesB) {}

    record CouplingRow(
            String fileA,
            String fileB,
            int coChanges,
            int totalChangesA,
            int totalChangesB,
            double couplingRatio) {}

    record PartnerRow(
            String partnerPath,
            int coChanges,
            int targetTotalChanges,
            int partnerTotalChanges,
            double couplingRatio) {}
}
