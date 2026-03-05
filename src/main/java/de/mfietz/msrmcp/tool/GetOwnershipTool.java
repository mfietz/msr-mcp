package de.mfietz.msrmcp.tool;

import de.mfietz.msrmcp.db.CommitDao;
import de.mfietz.msrmcp.db.CommitDao.OwnershipRow;
import io.modelcontextprotocol.spec.McpSchema.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static de.mfietz.msrmcp.tool.GetHotspotsTool.*;

/**
 * MCP tool: {@code get_ownership}
 *
 * <p>Returns the top owner (by commit count or lines added) per file,
 * along with their ownership ratio.
 */
public final class GetOwnershipTool {

    static final String NAME = "get_ownership";
    private static final JsonMapper MAPPER = JsonMapper.shared();

    private final CommitDao commitDao;

    public GetOwnershipTool(CommitDao commitDao) {
        this.commitDao = commitDao;
    }

    public CallToolResult handle(Map<String, Object> args) {
        int    topN         = intArg(args,    "topN",         20);
        String ownershipBy  = stringArg(args, "ownershipBy",  "commits");
        double minOwnership = doubleArg(args, "minOwnership", 0.0);
        String extension    = stringArg(args, "extension",    "");
        String pathFilter   = stringArg(args, "pathFilter",   "%");
        Long   sinceEpochMs = longArg(args,   "sinceEpochMs");

        String extensionPattern = extension.isEmpty() ? "%" : "%." + extension.replaceFirst("^\\.", "");

        List<OwnershipRow> rows;
        try {
            rows = switch (ownershipBy) {
                case "commits" -> commitDao.findOwnershipByCommits(sinceEpochMs, extensionPattern, pathFilter, minOwnership, topN);
                case "lines"   -> commitDao.findOwnershipByLines(sinceEpochMs, extensionPattern, pathFilter, minOwnership, topN);
                default        -> null;
            };
        } catch (Exception e) {
            return error("get_ownership failed: " + e.getMessage());
        }

        if (rows == null) {
            return error("ownershipBy must be 'commits' or 'lines'");
        }

        try {
            return ok(MAPPER.writeValueAsString(rows));
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    static Tool toolSpec() {
        return Tool.builder()
                .name(NAME)
                .description("""
                        Returns the top owner per file ranked by ownership ratio.
                        ownershipRatio = top author's share of commits (or lines added) for that file.
                        Useful for identifying knowledge silos and ownership concentration.
                        """)
                .inputSchema(ToolSchemas.ownership())
                .build();
    }
}
