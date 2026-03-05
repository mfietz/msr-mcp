package de.mfietz.msrmcp.db;

import de.mfietz.msrmcp.model.FileMetricsRecord;
import java.util.List;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

public interface FileMetricsDao {

    @SqlBatch(
            """
            INSERT OR REPLACE INTO file_metrics(file_id, loc, cyclomatic_complexity, cognitive_complexity, analyzed_at)
            VALUES(:fileId, :loc, :cyclomaticComplexity, :cognitiveComplexity, :analyzedAt)
            """)
    void upsertBatch(@BindMethods List<FileMetricsIdRecord> metrics);

    @SqlQuery(
            """
            SELECT f.path AS file_path, fm.loc, fm.cyclomatic_complexity, fm.cognitive_complexity, fm.analyzed_at
            FROM file_metrics fm
            JOIN files f ON f.file_id = fm.file_id
            WHERE f.path IN (<filePaths>)
            """)
    List<FileMetricsRecord> findByPaths(@BindList("filePaths") List<String> filePaths);

    @SqlQuery("SELECT COUNT(*) FROM file_metrics")
    int count();

    @SqlQuery("SELECT f.path FROM file_metrics fm JOIN files f ON f.file_id = fm.file_id")
    List<String> findAllFilePaths();

    @SqlUpdate(
            "DELETE FROM file_metrics WHERE file_id IN (SELECT file_id FROM files WHERE path IN (<paths>))")
    void deleteByPaths(@BindList("paths") List<String> paths);

    record FileMetricsIdRecord(
            long fileId,
            int loc,
            int cyclomaticComplexity,
            int cognitiveComplexity,
            long analyzedAt) {}
}
