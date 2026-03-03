package com.example.msrmcp.db;

import com.example.msrmcp.model.CommitRecord;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;

public interface CommitDao {

    @SqlUpdate("""
            INSERT OR IGNORE INTO commits(hash, author_date, first_line, jira_slug)
            VALUES(:hash, :authorDate, :firstLine, :jiraSlug)
            """)
    void insert(@BindMethods CommitRecord c);

    @SqlBatch("""
            INSERT OR IGNORE INTO commits(hash, author_date, first_line, jira_slug)
            VALUES(:hash, :authorDate, :firstLine, :jiraSlug)
            """)
    void insertBatch(@BindMethods List<CommitRecord> commits);

    @SqlQuery("SELECT hash FROM commits ORDER BY author_date DESC LIMIT 1")
    Optional<String> findLatestHash();

    @SqlQuery("SELECT COUNT(*) FROM commits")
    int count();
}
