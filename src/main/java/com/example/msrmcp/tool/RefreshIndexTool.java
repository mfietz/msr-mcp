package com.example.msrmcp.tool;

import com.example.msrmcp.db.Database;
import com.example.msrmcp.index.Indexer;
import com.example.msrmcp.model.IndexResult;
import io.modelcontextprotocol.spec.McpSchema.*;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.Map;

import static com.example.msrmcp.tool.GetHotspotsTool.error;
import static com.example.msrmcp.tool.GetHotspotsTool.ok;

/**
 * MCP tool: {@code refresh_index}
 *
 * <p>Clears the co-change data and re-indexes the full repository.
 * No arguments required.
 */
public final class RefreshIndexTool {

    static final String NAME = "refresh_index";
    private static final JsonMapper MAPPER = JsonMapper.shared();

    private final Path repoDir;
    private final Database db;

    public RefreshIndexTool(Path repoDir, Database db) {
        this.repoDir = repoDir;
        this.db = db;
    }

    public CallToolResult handle(Map<String, Object> args) {
        try {
            IndexResult result = Indexer.runFull(repoDir, db);
            return ok(MAPPER.writeValueAsString(result));
        } catch (Exception e) {
            return error("refresh_index failed: " + e.getMessage());
        }
    }

    static Tool toolSpec() {
        return Tool.builder()
                .name(NAME)
                .description("""
                        Clears and rebuilds the full MSR index from git history.
                        Returns an IndexResult with status, counts, and duration.
                        No arguments required.
                        """)
                .inputSchema(ToolSchemas.empty())
                .build();
    }
}
