package com.example.msrmcp.db;

import com.example.msrmcp.model.*;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.reflect.ConstructorMapper;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

import java.nio.file.Path;
import java.util.List;

/**
 * Opens (or creates) the SQLite database, applies pragmas and DDL,
 * and exposes a pre-configured Jdbi instance.
 */
public final class Database {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS commits (
                commit_id    INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                hash         TEXT    NOT NULL UNIQUE,
                author_date  INTEGER NOT NULL,
                first_line   TEXT    NOT NULL,
                jira_slug    TEXT,
                author_email TEXT,
                author_name  TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_commits_author_date  ON commits(author_date);
            CREATE INDEX IF NOT EXISTS idx_commits_jira_slug    ON commits(jira_slug) WHERE jira_slug IS NOT NULL;
            CREATE INDEX IF NOT EXISTS idx_commits_author_email ON commits(author_email);

            CREATE TABLE IF NOT EXISTS files (
                file_id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                path    TEXT    NOT NULL UNIQUE
            );

            CREATE TABLE IF NOT EXISTS file_changes (
                id           INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                commit_id    INTEGER NOT NULL REFERENCES commits(commit_id),
                file_id      INTEGER NOT NULL REFERENCES files(file_id),
                lines_added  INTEGER NOT NULL DEFAULT 0,
                lines_deleted INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS idx_file_changes_commitid ON file_changes(commit_id);
            CREATE INDEX IF NOT EXISTS idx_file_changes_fileid_commitid ON file_changes(file_id, commit_id);

            CREATE TABLE IF NOT EXISTS file_metrics (
                file_id               INTEGER NOT NULL PRIMARY KEY REFERENCES files(file_id),
                loc                   INTEGER NOT NULL,
                cyclomatic_complexity INTEGER NOT NULL DEFAULT -1,
                cognitive_complexity  INTEGER NOT NULL DEFAULT -1,
                analyzed_at           INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS file_coupling (
                file_a_id       INTEGER NOT NULL REFERENCES files(file_id),
                file_b_id       INTEGER NOT NULL REFERENCES files(file_id),
                co_changes      INTEGER NOT NULL DEFAULT 0,
                total_changes_a INTEGER NOT NULL DEFAULT 0,
                total_changes_b INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (file_a_id, file_b_id)
            );
            CREATE INDEX IF NOT EXISTS idx_coupling_a ON file_coupling(file_a_id);
            CREATE INDEX IF NOT EXISTS idx_coupling_b ON file_coupling(file_b_id);
            """;

    private final Jdbi jdbi;

    private Database(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    public static Database open(Path dbPath) {
        Jdbi jdbi = Jdbi.create("jdbc:sqlite:" + dbPath.toAbsolutePath());
        jdbi.installPlugin(new SqlObjectPlugin());

        // Register ConstructorMapper for all record types so JDBI can map
        // snake_case columns to camelCase record components automatically.
        jdbi.registerRowMapper(ConstructorMapper.factory(FileDao.FileRecord.class));
        jdbi.registerRowMapper(ConstructorMapper.factory(CommitRecord.class));
        jdbi.registerRowMapper(ConstructorMapper.factory(CommitDao.CommitIdRecord.class));
        jdbi.registerRowMapper(ConstructorMapper.factory(FileChangeRecord.class));
        jdbi.registerRowMapper(ConstructorMapper.factory(FileMetricsRecord.class));
        jdbi.registerRowMapper(ConstructorMapper.factory(FileCouplingRecord.class));
        jdbi.registerRowMapper(ConstructorMapper.factory(FileChangeDao.FileChangeFrequencyRow.class));
        jdbi.registerRowMapper(ConstructorMapper.factory(FileChangeDao.ChurnRow.class));
        jdbi.registerRowMapper(ConstructorMapper.factory(FileCouplingDao.CouplingRow.class));
        jdbi.registerRowMapper(ConstructorMapper.factory(FileCouplingDao.PartnerRow.class));
        jdbi.registerRowMapper(ConstructorMapper.factory(CommitDao.AuthorRow.class));
        jdbi.registerRowMapper(ConstructorMapper.factory(CommitDao.BusFactorRow.class));
        jdbi.registerRowMapper(ConstructorMapper.factory(CommitDao.OwnershipRow.class));

        // WAL mode for better concurrent read performance
        jdbi.useHandle(h -> h.execute("PRAGMA journal_mode=WAL"));

        // Migrations for existing databases (ALTER TABLE ignores duplicate columns via try-catch)
        jdbi.useHandle(h -> {
            for (String col : List.of("author_email TEXT", "author_name TEXT")) {
                try { h.execute("ALTER TABLE commits ADD COLUMN " + col); } catch (Exception ignored) {}
            }
            for (String col : List.of("lines_added INTEGER NOT NULL DEFAULT 0",
                                      "lines_deleted INTEGER NOT NULL DEFAULT 0")) {
                try { h.execute("ALTER TABLE file_changes ADD COLUMN " + col); } catch (Exception ignored) {}
            }
        });

        // Apply schema (idempotent)
        jdbi.useHandle(h -> {
            for (String stmt : DDL.split(";")) {
                String sql = stmt.strip();
                if (!sql.isBlank()) {
                    h.execute(sql);
                }
            }
        });

        return new Database(jdbi);
    }

    public Jdbi jdbi() {
        return jdbi;
    }

    public <T> T attach(Class<T> daoType) {
        return jdbi.onDemand(daoType);
    }
}
