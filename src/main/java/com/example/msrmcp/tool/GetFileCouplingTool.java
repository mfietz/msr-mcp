package com.example.msrmcp.tool;

import com.example.msrmcp.db.FileCouplingDao;
import com.example.msrmcp.db.FileCouplingDao.PartnerRow;
import io.modelcontextprotocol.spec.McpSchema.*;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * MCP tool: {@code get_file_coupling}
 *
 * <p>Returns the files most temporally coupled to a specific file, ranked by
 * coupling ratio. Useful for understanding blast radius and hidden dependencies.
 *
 * <p>Arguments:
 * <ul>
 *   <li>{@code filePath} (string, required) — repo-relative path, e.g. {@code "src/Main.java"}
 *   <li>{@code topN} (int, default 10) — number of partner files to return
 *   <li>{@code minCoupling} (double, default 0.1) — minimum coupling ratio 0–1
 * </ul>
 */
public final class GetFileCouplingTool {

    static final String NAME = "get_file_coupling";
    private static final JsonMapper MAPPER = JsonMapper.shared();

    private final FileCouplingDao fileCouplingDao;

    public GetFileCouplingTool(FileCouplingDao fileCouplingDao) {
        this.fileCouplingDao = fileCouplingDao;
    }

    public CallToolResult handle(Map<String, Object> args) {
        try {
            String filePath   = GetHotspotsTool.stringArg(args, "filePath", null);
            if (filePath == null || filePath.isBlank()) {
                return GetHotspotsTool.error("filePath is required");
            }
            int    topN       = GetHotspotsTool.intArg(args, "topN", 10);
            double minCoupling = doubleArg(args, "minCoupling", 0.1);

            List<PartnerRow> rows =
                    fileCouplingDao.findTopCoupledForFile(filePath, minCoupling, topN);

            return GetHotspotsTool.ok(MAPPER.writeValueAsString(rows));
        } catch (Exception e) {
            return GetHotspotsTool.error("get_file_coupling failed: " + e.getMessage());
        }
    }

    static Tool toolSpec() {
        return Tool.builder()
                .name(NAME)
                .description("""
                        Returns the files most frequently changed together with the given file,
                        ranked by coupling ratio. Useful for impact analysis and understanding
                        hidden dependencies.
                        """)
                .inputSchema(ToolSchemas.fileCoupling())
                .build();
    }

    private static double doubleArg(Map<String, Object> args, String key, double def) {
        Object v = args.get(key);
        if (v == null) return def;
        return ((Number) v).doubleValue();
    }
}
