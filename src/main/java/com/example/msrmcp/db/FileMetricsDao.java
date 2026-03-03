package com.example.msrmcp.db;

import com.example.msrmcp.model.FileMetricsRecord;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

import java.util.List;

public interface FileMetricsDao {

    @SqlBatch("""
            INSERT OR REPLACE INTO file_metrics(file_path, loc, cyclomatic_complexity, cognitive_complexity, analyzed_at)
            VALUES(:filePath, :loc, :cyclomaticComplexity, :cognitiveComplexity, :analyzedAt)
            """)
    void upsertBatch(@BindMethods List<FileMetricsRecord> metrics);

    /**
     * @param filePaths use angle-bracket syntax: <filePaths>
     */
    @SqlQuery("SELECT file_path, loc, cyclomatic_complexity, cognitive_complexity, analyzed_at FROM file_metrics WHERE file_path IN (<filePaths>)")
    List<FileMetricsRecord> findByPaths(@BindList("filePaths") List<String> filePaths);

    @SqlQuery("SELECT COUNT(*) FROM file_metrics")
    int count();
}
