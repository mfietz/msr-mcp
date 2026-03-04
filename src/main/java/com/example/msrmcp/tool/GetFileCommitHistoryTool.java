package com.example.msrmcp.tool;

import com.example.msrmcp.db.CommitDao;
import com.example.msrmcp.db.FileChangeDao;
import io.modelcontextprotocol.spec.McpSchema.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

import static com.example.msrmcp.tool.GetHotspotsTool.*;

/**
 * MCP tool: {@code get_file_commit_history}
 *
 * <p>Required argument:
 * <ul>
 *   <li>{@code filePath} (String) — repo-relative path, e.g. "src/Main.java"
 * </ul>
 * Optional:
 * <ul>
 *   <li>{@code limit} (int, default 50)
 *   <li>{@code sinceEpochMs} (long)
 * </ul>
 */
public final class GetFileCommitHistoryTool {

    static final String NAME = "get_file_commit_history";
    private static final JsonMapper MAPPER = JsonMapper.shared();

    private final CommitDao commitDao;
    private final FileChangeDao fileChangeDao;

    public GetFileCommitHistoryTool(CommitDao commitDao, FileChangeDao fileChangeDao) {
        this.commitDao = commitDao;
        this.fileChangeDao = fileChangeDao;
    }

    public CallToolResult handle(Map<String, Object> args) {
        try {
            String filePath = stringArg(args, "filePath", null);
            if (filePath == null || filePath.isBlank()) {
                return error("filePath is required");
            }
            int    limit       = intArg(args, "limit", 50);
            Long sinceEpochMs  = longArg(args, "sinceEpochMs");
            String jiraSlug    = stringArg(args, "jiraSlug", null);

            List<String> hashes = fileChangeDao.findCommitHashesForFile(filePath, sinceEpochMs, jiraSlug, limit);

            List<Map<String, Object>> result = new ArrayList<>(hashes.size());
            for (String hash : hashes) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("hash", hash);
                commitDao.findByHash(hash).ifPresent(c -> {
                    entry.put("authorDate", c.authorDate());
                    entry.put("firstLine", c.firstLine());
                    entry.put("jiraSlug", c.jiraSlug());
                });
                entry.put("filesChanged", fileChangeDao.findPathsByCommit(hash));
                result.add(entry);
            }

            return ok(MAPPER.writeValueAsString(result));
        } catch (Exception e) {
            return error("get_file_commit_history failed: " + e.getMessage());
        }
    }

    static Tool toolSpec() {
        return Tool.builder()
                .name(NAME)
                .description("""
                        Returns commit history for a specific file in reverse chronological order.
                        Each entry includes hash, authorDate, firstLine, jiraSlug, and filesChanged.
                        """)
                .inputSchema(ToolSchemas.fileCommitHistory())
                .build();
    }
}
