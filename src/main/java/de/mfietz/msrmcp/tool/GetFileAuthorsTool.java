package de.mfietz.msrmcp.tool;

import static de.mfietz.msrmcp.tool.GetHotspotsTool.*;

import de.mfietz.msrmcp.db.CommitDao;
import de.mfietz.msrmcp.db.CommitDao.AuthorRow;
import io.modelcontextprotocol.spec.McpSchema.*;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP tool: {@code get_file_authors}
 *
 * <p>Returns the authors who changed a specific file most often, ranked by commit count. Useful for
 * identifying knowledge owners and bus-factor risks.
 */
public final class GetFileAuthorsTool {

    static final String NAME = "get_file_authors";
    private static final JsonMapper MAPPER = JsonMapper.shared();

    private final CommitDao commitDao;

    public GetFileAuthorsTool(CommitDao commitDao) {
        this.commitDao = commitDao;
    }

    public CallToolResult handle(Map<String, Object> args) {
        try {
            String filePath = stringArg(args, "filePath", null);
            if (filePath == null || filePath.isBlank()) {
                return error("filePath is required");
            }
            int topN = intArg(args, "topN", 10);
            Long sinceEpochMs = longArg(args, "sinceEpochMs");

            List<AuthorRow> rows = commitDao.findAuthorsForFile(filePath, sinceEpochMs, topN);
            return ok(MAPPER.writeValueAsString(rows));
        } catch (Exception e) {
            return error("get_file_authors failed: " + e.getMessage());
        }
    }

    static Tool toolSpec() {
        return Tool.builder()
                .name(NAME)
                .description(
                        """
                        Returns the authors who changed a specific file most often, ranked by commit count.
                        Useful for identifying knowledge owners and single-point-of-failure risks (bus factor).
                        """)
                .inputSchema(ToolSchemas.fileAuthors())
                .build();
    }
}
