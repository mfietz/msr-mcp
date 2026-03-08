package de.mfietz.msrmcp.db;

import java.util.List;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

public interface FileDao {

    @SqlBatch("INSERT OR IGNORE INTO files(path) VALUES(:path)")
    void insertBatch(@Bind("path") List<String> paths);

    @SqlQuery("SELECT file_id, path FROM files WHERE path IN (<paths>)")
    List<FileRecord> findByPaths(@BindList("paths") List<String> paths);

    @SqlQuery("SELECT path FROM files")
    List<String> findAllPaths();

    @SqlUpdate("UPDATE files SET path = :newPath WHERE path = :oldPath")
    void updatePath(@Bind("oldPath") String oldPath, @Bind("newPath") String newPath);

    record FileRecord(long fileId, String path) {}
}
