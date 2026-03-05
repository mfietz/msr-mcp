package de.mfietz.msrmcp.tool;

import de.mfietz.msrmcp.db.CommitDao;
import de.mfietz.msrmcp.db.CommitDao.BusFactorRow;
import io.modelcontextprotocol.spec.McpSchema.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static de.mfietz.msrmcp.tool.GetHotspotsTool.*;

/**
 * MCP tool: {@code get_bus_factor}
 *
 * <p>Returns files where a single author is responsible for a disproportionate share
 * of commits (dominance ratio ≥ threshold). High dominance = high bus-factor risk.
 */
public final class GetBusFactorTool {

    static final String NAME = "get_bus_factor";
    private static final JsonMapper MAPPER = JsonMapper.shared();

    private final CommitDao commitDao;

    public GetBusFactorTool(CommitDao commitDao) {
        this.commitDao = commitDao;
    }

    public CallToolResult handle(Map<String, Object> args) {
        try {
            int    topN        = intArg(args, "topN", 20);
            double threshold   = doubleArg(args, "threshold", 0.75);
            String pathFilter  = stringArg(args, "pathFilter", null);
            Long sinceEpochMs  = longArg(args, "sinceEpochMs");

            List<BusFactorRow> rows =
                    commitDao.findBusFactorFiles(sinceEpochMs, threshold, pathFilter, topN);
            return ok(MAPPER.writeValueAsString(rows));
        } catch (Exception e) {
            return error("get_bus_factor failed: " + e.getMessage());
        }
    }

    static Tool toolSpec() {
        return Tool.builder()
                .name(NAME)
                .description("""
                        Returns files where one author is responsible for a disproportionate share of commits.
                        dominanceRatio = top author's commits / total commits for that file.
                        Files with dominanceRatio >= threshold are returned, sorted by dominanceRatio descending.
                        Useful for identifying single points of failure and knowledge silos.
                        """)
                .inputSchema(ToolSchemas.busFactor())
                .build();
    }

}
