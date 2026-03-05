package de.mfietz.msrmcp.tool;

import de.mfietz.msrmcp.db.FileChangeDao;
import de.mfietz.msrmcp.db.FileChangeDao.ChurnRow;
import io.modelcontextprotocol.spec.McpSchema.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static de.mfietz.msrmcp.tool.GetHotspotsTool.*;

/**
 * MCP tool: {@code get_churn}
 *
 * <p>Returns files ranked by total churn (lines added + lines deleted).
 * Useful for identifying files with high edit volume regardless of complexity.
 */
public final class GetChurnTool {

    static final String NAME = "get_churn";
    private static final JsonMapper MAPPER = JsonMapper.shared();

    private final FileChangeDao fileChangeDao;

    public GetChurnTool(FileChangeDao fileChangeDao) {
        this.fileChangeDao = fileChangeDao;
    }

    public CallToolResult handle(Map<String, Object> args) {
        try {
            int topN = intArg(args, "topN", 20);
            Long sinceEpochMs = longArg(args, "sinceEpochMs");
            String ext = stringArg(args, "extension", "");
            String extPattern = "%" + ext;
            String pathFilter = stringArg(args, "pathFilter", "%");

            List<ChurnRow> rows = fileChangeDao.findTopChurn(sinceEpochMs, extPattern, pathFilter, topN);
            return ok(MAPPER.writeValueAsString(rows));
        } catch (Exception e) {
            return error("get_churn failed: " + e.getMessage());
        }
    }

    static Tool toolSpec() {
        return Tool.builder()
                .name(NAME)
                .description("""
                        Returns the top-N files ranked by total churn (lines added + lines deleted).
                        Useful for finding files with the most edit volume.
                        Optionally filter by time window (sinceEpochMs) and file extension.
                        """)
                .inputSchema(ToolSchemas.churn())
                .build();
    }
}
