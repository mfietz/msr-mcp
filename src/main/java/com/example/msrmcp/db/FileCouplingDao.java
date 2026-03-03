package com.example.msrmcp.db;

import com.example.msrmcp.model.FileCouplingRecord;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;

public interface FileCouplingDao {

    @SqlBatch("""
            INSERT INTO file_coupling(file_a, file_b, co_changes, total_changes_a, total_changes_b)
            VALUES(:fileA, :fileB, :coChanges, :totalChangesA, :totalChangesB)
            ON CONFLICT(file_a, file_b) DO UPDATE SET
              co_changes      = co_changes      + excluded.co_changes,
              total_changes_a = total_changes_a + excluded.total_changes_a,
              total_changes_b = total_changes_b + excluded.total_changes_b
            """)
    void upsertBatch(@BindMethods List<FileCouplingRecord> records);

    /**
     * Use the pre-aggregated table (fast, no time filter).
     * fileFilter e.g. "%.java" or null for all.
     */
    @SqlQuery("""
            SELECT
              file_a, file_b, co_changes, total_changes_a, total_changes_b,
              CAST(co_changes AS REAL) / MIN(total_changes_a, total_changes_b) AS coupling_ratio
            FROM file_coupling
            WHERE (:fileFilter IS NULL OR file_a LIKE :fileFilter OR file_b LIKE :fileFilter)
              AND total_changes_a > 0 AND total_changes_b > 0
              AND CAST(co_changes AS REAL) / MIN(total_changes_a, total_changes_b) >= :minCoupling
            ORDER BY coupling_ratio DESC
            LIMIT :topN
            """)
    List<CouplingRow> findTopCoupled(
            @Bind("minCoupling") double minCoupling,
            @Bind("fileFilter") String fileFilter,
            @Bind("topN") int topN);

    /**
     * Computes coupling dynamically from raw file_changes within a time window (slower).
     */
    @SqlQuery("""
            WITH recent AS (
              SELECT fc.file_path, fc.commit_hash
              FROM file_changes fc
              JOIN commits c ON c.hash = fc.commit_hash
              WHERE c.author_date >= :sinceEpochMs
            ),
            totals AS (
              SELECT file_path, COUNT(DISTINCT commit_hash) AS total_changes
              FROM recent
              GROUP BY file_path
            )
            SELECT
              a.file_path AS file_a,
              b.file_path AS file_b,
              COUNT(DISTINCT a.commit_hash) AS co_changes,
              ta.total_changes AS total_changes_a,
              tb.total_changes AS total_changes_b,
              CAST(COUNT(DISTINCT a.commit_hash) AS REAL) / MIN(ta.total_changes, tb.total_changes) AS coupling_ratio
            FROM recent a
            JOIN recent b ON b.commit_hash = a.commit_hash AND a.file_path < b.file_path
            JOIN totals ta ON ta.file_path = a.file_path
            JOIN totals tb ON tb.file_path = b.file_path
            WHERE (:fileFilter IS NULL OR a.file_path LIKE :fileFilter OR b.file_path LIKE :fileFilter)
            GROUP BY a.file_path, b.file_path
            HAVING coupling_ratio >= :minCoupling
            ORDER BY coupling_ratio DESC
            LIMIT :topN
            """)
    List<CouplingRow> findTopCoupledSince(
            @Bind("sinceEpochMs") Long sinceEpochMs,
            @Bind("fileFilter") String fileFilter,
            @Bind("minCoupling") double minCoupling,
            @Bind("topN") int topN);

    @SqlUpdate("DELETE FROM file_coupling")
    void deleteAll();

    record CouplingRow(
            String fileA,
            String fileB,
            int coChanges,
            int totalChangesA,
            int totalChangesB,
            double couplingRatio) {}
}
