package com.example.msrmcp.tool;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import java.util.List;
import java.util.Map;

/** JSON Schema definitions for all tool input schemas. */
final class ToolSchemas {

    private ToolSchemas() {}

    static JsonSchema hotspots() {
        return new JsonSchema("object", Map.of(
                "topN", Map.of("type", "integer", "description", "Max results (default 20)"),
                "sinceEpochMs", Map.of("type", "integer",
                        "description", "Only include commits after this Unix timestamp in ms"),
                "extension", Map.of("type", "string",
                        "description", "File extension filter, e.g. \".java\" or \".ts\". Default: all files"),
                "pathFilter", Map.of("type", "string",
                        "description", "SQL LIKE path pattern, e.g. \"src/service/%\". Default: all paths")),
                List.of(), null, null, null);
    }

    static JsonSchema temporalCoupling() {
        return new JsonSchema("object", Map.of(
                "topN", Map.of("type", "integer", "description", "Max results (default 20)"),
                "minCoupling", Map.of("type", "number",
                        "description", "Minimum coupling ratio 0–1 (default 0.3)"),
                "fileFilter", Map.of("type", "string",
                        "description", "SQL LIKE pattern, e.g. \"%.java\""),
                "sinceEpochMs", Map.of("type", "integer",
                        "description", "Time window start (ms); triggers dynamic query when set")),
                List.of(), null, null, null);
    }

    static JsonSchema fileCommitHistory() {
        return new JsonSchema("object", Map.of(
                "filePath", Map.of("type", "string",
                        "description", "Repo-relative file path, e.g. \"src/Main.java\""),
                "limit", Map.of("type", "integer", "description", "Max commits (default 50)"),
                "sinceEpochMs", Map.of("type", "integer",
                        "description", "Only include commits after this Unix timestamp in ms")),
                List.of("filePath"), null, null, null);
    }

    static JsonSchema empty() {
        return new JsonSchema("object", Map.of(), List.of(), null, null, null);
    }
}
