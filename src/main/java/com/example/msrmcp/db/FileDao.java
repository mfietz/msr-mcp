package com.example.msrmcp.db;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;

public interface FileDao {

    @SqlBatch("INSERT OR IGNORE INTO files(path) VALUES(:path)")
    void insertBatch(@Bind("path") List<String> paths);

    @SqlQuery("SELECT file_id, path FROM files WHERE path IN (<paths>)")
    List<FileRecord> findByPaths(@BindList("paths") List<String> paths);

    @SqlQuery("SELECT file_id FROM files WHERE path = :path")
    List<Long> findIdByPath(@Bind("path") String path);

    @SqlUpdate("UPDATE files SET path = :newPath WHERE path = :oldPath")
    void updatePath(@Bind("oldPath") String oldPath, @Bind("newPath") String newPath);

    @SqlUpdate("DELETE FROM files WHERE file_id = :fileId")
    void deleteById(@Bind("fileId") long fileId);

    record FileRecord(long fileId, String path) {}
}
