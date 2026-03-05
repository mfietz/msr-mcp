package de.mfietz.msrmcp.tool;

import de.mfietz.msrmcp.db.FileCouplingDao;
import de.mfietz.msrmcp.db.FileCouplingDao.CouplingRow;
import de.mfietz.msrmcp.db.FileChangeDao;
import io.modelcontextprotocol.spec.McpSchema.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static de.mfietz.msrmcp.tool.GetHotspotsTool.*;

/**
 * MCP tool: {@code get_temporal_coupling}
 *
 * <p>Without {@code sinceEpochMs}: uses the fast pre-aggregated {@code file_coupling} table.
 * With {@code sinceEpochMs}: runs a CTE-based dynamic query on raw {@code file_changes}.
 */
public final class GetTemporalCouplingTool {

    static final String NAME = "get_temporal_coupling";
    private static final JsonMapper MAPPER = JsonMapper.shared();

    private final FileCouplingDao fileCouplingDao;
    private final FileChangeDao fileChangeDao;

    public GetTemporalCouplingTool(FileCouplingDao fileCouplingDao, FileChangeDao fileChangeDao) {
        this.fileCouplingDao = fileCouplingDao;
        this.fileChangeDao = fileChangeDao;
    }

    public CallToolResult handle(Map<String, Object> args) {
        try {
            int topN           = intArg(args, "topN", 20);
            double minCoupling = doubleArg(args, "minCoupling", 0.3);
            String fileFilter  = stringArg(args, "fileFilter", null);
            Long sinceEpochMs  = longArg(args, "sinceEpochMs");

            List<CouplingRow> rows = sinceEpochMs != null
                    ? fileCouplingDao.findTopCoupledSince(sinceEpochMs, fileFilter, minCoupling, topN)
                    : fileCouplingDao.findTopCoupled(minCoupling, fileFilter, topN);

            return ok(MAPPER.writeValueAsString(rows));
        } catch (Exception e) {
            return error("get_temporal_coupling failed: " + e.getMessage());
        }
    }

    static Tool toolSpec() {
        return Tool.builder()
                .name(NAME)
                .description("""
                        Returns file pairs that are frequently changed together (temporal coupling).
                        Without sinceEpochMs uses the fast pre-aggregated table.
                        With sinceEpochMs runs a dynamic query scoped to that time window.
                        """)
                .inputSchema(ToolSchemas.temporalCoupling())
                .build();
    }

    private static double doubleArg(Map<String, Object> args, String key, double def) {
        Object v = args.get(key);
        if (v == null) return def;
        return ((Number) v).doubleValue();
    }
}
