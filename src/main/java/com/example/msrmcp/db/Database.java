package com.example.msrmcp.db;

import com.example.msrmcp.model.*;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.reflect.ConstructorMapper;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

import java.nio.file.Path;

/**
 * Opens (or creates) the SQLite database, applies pragmas and DDL,
 * and exposes a pre-configured Jdbi instance.
 */
public final class Database {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS commits (
                hash        TEXT    NOT NULL PRIMARY KEY,
                author_date INTEGER NOT NULL,
                first_line  TEXT    NOT NULL,
                jira_slug   TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_commits_author_date ON commits(author_date);

            CREATE TABLE IF NOT EXISTS file_changes (
                id          INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                commit_hash TEXT    NOT NULL REFERENCES commits(hash),
                file_path   TEXT    NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_file_changes_commit_hash ON file_changes(commit_hash);
            CREATE INDEX IF NOT EXISTS idx_file_changes_file_path   ON file_changes(file_path);

            CREATE TABLE IF NOT EXISTS file_metrics (
                file_path             TEXT    NOT NULL PRIMARY KEY,
                loc                   INTEGER NOT NULL,
                cyclomatic_complexity INTEGER NOT NULL DEFAULT -1,
                cognitive_complexity  INTEGER NOT NULL DEFAULT -1,
                analyzed_at           INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS file_coupling (
                file_a          TEXT    NOT NULL,
                file_b          TEXT    NOT NULL,
                co_changes      INTEGER NOT NULL DEFAULT 0,
                total_changes_a INTEGER NOT NULL DEFAULT 0,
                total_changes_b INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (file_a, file_b)
            );
            CREATE INDEX IF NOT EXISTS idx_coupling_a ON file_coupling(file_a);
            CREATE INDEX IF NOT EXISTS idx_coupling_b ON file_coupling(file_b);
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
        jdbi.registerRowMapper(ConstructorMapper.factory(CommitRecord.class));
        jdbi.registerRowMapper(ConstructorMapper.factory(FileChangeRecord.class));
        jdbi.registerRowMapper(ConstructorMapper.factory(FileMetricsRecord.class));
        jdbi.registerRowMapper(ConstructorMapper.factory(FileCouplingRecord.class));
        jdbi.registerRowMapper(ConstructorMapper.factory(FileChangeDao.FileChangeFrequencyRow.class));
        jdbi.registerRowMapper(ConstructorMapper.factory(FileCouplingDao.CouplingRow.class));
        jdbi.registerRowMapper(ConstructorMapper.factory(FileCouplingDao.PartnerRow.class));

        // WAL mode for better concurrent read performance
        jdbi.useHandle(h -> h.execute("PRAGMA journal_mode=WAL"));

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
