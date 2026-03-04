package com.example.msrmcp.db;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

import java.util.List;

public interface FileDao {

    @SqlBatch("INSERT OR IGNORE INTO files(path) VALUES(:path)")
    void insertBatch(@Bind("path") List<String> paths);

    @SqlQuery("SELECT file_id, path FROM files WHERE path IN (<paths>)")
    List<FileRecord> findByPaths(@BindList("paths") List<String> paths);

    record FileRecord(long fileId, String path) {}
}
