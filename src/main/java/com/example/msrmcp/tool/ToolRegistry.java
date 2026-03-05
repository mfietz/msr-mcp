package com.example.msrmcp.tool;

import com.example.msrmcp.db.*;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.nio.file.Path;
import java.util.List;

/**
 * Builds all {@link McpServerFeatures.SyncToolSpecification} instances for registration
 * on the MCP server builder via {@code .tools(list)}.
 */
public final class ToolRegistry {

    private ToolRegistry() {}

    public static List<McpServerFeatures.SyncToolSpecification> buildSpecs(
            CommitDao commitDao,
            FileChangeDao fileChangeDao,
            FileMetricsDao fileMetricsDao,
            FileCouplingDao fileCouplingDao,
            Path repoDir,
            Database db) {

        FileDao fileDao = db.attach(FileDao.class);

        GetHotspotsTool hotspots = new GetHotspotsTool(fileChangeDao, fileMetricsDao);
        GetTemporalCouplingTool coupling =
                new GetTemporalCouplingTool(fileCouplingDao, fileChangeDao);
        GetFileCommitHistoryTool history =
                new GetFileCommitHistoryTool(commitDao, fileChangeDao);
        RefreshIndexTool refresh = new RefreshIndexTool(repoDir, db);
        GetSummaryTool summary =
                new GetSummaryTool(commitDao, fileChangeDao, fileMetricsDao, fileDao);
        GetFileCouplingTool fileCoupling = new GetFileCouplingTool(fileCouplingDao);
        GetFileAuthorsTool fileAuthors = new GetFileAuthorsTool(commitDao);
        GetBusFactorTool busFactor = new GetBusFactorTool(commitDao);
        GetChurnTool churn = new GetChurnTool(fileChangeDao);
        GetOwnershipTool ownership = new GetOwnershipTool(commitDao);

        return List.of(
                new McpServerFeatures.SyncToolSpecification(
                        GetHotspotsTool.toolSpec(),
                        (exchange, req) -> hotspots.handle(req.arguments())),

                new McpServerFeatures.SyncToolSpecification(
                        GetTemporalCouplingTool.toolSpec(),
                        (exchange, req) -> coupling.handle(req.arguments())),

                new McpServerFeatures.SyncToolSpecification(
                        GetFileCommitHistoryTool.toolSpec(),
                        (exchange, req) -> history.handle(req.arguments())),

                new McpServerFeatures.SyncToolSpecification(
                        RefreshIndexTool.toolSpec(),
                        (exchange, req) -> refresh.handle(req.arguments())),

                new McpServerFeatures.SyncToolSpecification(
                        GetSummaryTool.toolSpec(),
                        (exchange, req) -> summary.handle(req.arguments())),

                new McpServerFeatures.SyncToolSpecification(
                        GetFileCouplingTool.toolSpec(),
                        (exchange, req) -> fileCoupling.handle(req.arguments())),

                new McpServerFeatures.SyncToolSpecification(
                        GetFileAuthorsTool.toolSpec(),
                        (exchange, req) -> fileAuthors.handle(req.arguments())),

                new McpServerFeatures.SyncToolSpecification(
                        GetBusFactorTool.toolSpec(),
                        (exchange, req) -> busFactor.handle(req.arguments())),

                new McpServerFeatures.SyncToolSpecification(
                        GetChurnTool.toolSpec(),
                        (exchange, req) -> churn.handle(req.arguments())),

                new McpServerFeatures.SyncToolSpecification(
                        GetOwnershipTool.toolSpec(),
                        (exchange, req) -> ownership.handle(req.arguments()))
        );
    }
}
