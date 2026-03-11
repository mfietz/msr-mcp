package de.mfietz.msrmcp.tool;

import de.mfietz.msrmcp.index.IndexTracker;
import io.modelcontextprotocol.spec.McpSchema.*;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP tool: {@code get_index_status}
 *
 * <p>Returns the current state of the background index run: {@code not_started}, {@code indexing},
 * {@code ready}, or {@code error}. Callers should invoke this tool before relying on analytics
 * results to confirm the index is ready.
 */
public final class GetIndexStatusTool {

    static final String NAME = "get_index_status";
    private static final JsonMapper MAPPER = JsonMapper.shared();

    private final IndexTracker tracker;

    public GetIndexStatusTool(IndexTracker tracker) {
        this.tracker = tracker;
    }

    public CallToolResult handle(Map<String, Object> args) {
        try {
            String status = tracker.state().name().toLowerCase();
            StringBuilder sb = new StringBuilder();
            sb.append("{\"status\":\"").append(status).append("\"");
            if (tracker.startedAtMs() > 0) {
                sb.append(",\"startedAtMs\":").append(tracker.startedAtMs());
            }
            if (tracker.state() == IndexTracker.State.READY) {
                sb.append(",\"elapsedMs\":").append(tracker.elapsedMs());
            }
            if (tracker.state() == IndexTracker.State.ERROR && tracker.errorMessage() != null) {
                sb.append(",\"errorMessage\":\"")
                        .append(tracker.errorMessage().replace("\"", "\\\""))
                        .append("\"");
            }
            sb.append("}");
            return GetHotspotsTool.ok(sb.toString());
        } catch (Exception e) {
            return GetHotspotsTool.error("get_index_status failed: " + e.getMessage());
        }
    }

    static Tool toolSpec() {
        return Tool.builder()
                .name(NAME)
                .description(
                        """
                        Returns the current state of the background index run.
                        Possible values for 'status': not_started, indexing, ready, error.
                        Also returns startedAtMs (epoch ms when indexing began),
                        elapsedMs (total duration, only when ready),
                        and errorMessage (only when error).
                        Call this before analytics tools to confirm the index is ready.
                        No arguments required.
                        """)
                .inputSchema(ToolSchemas.empty())
                .build();
    }
}
