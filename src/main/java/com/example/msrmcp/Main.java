package com.example.msrmcp;

import com.example.msrmcp.db.*;
import com.example.msrmcp.index.Indexer;
import com.example.msrmcp.model.IndexResult;
import com.example.msrmcp.tool.ToolRegistry;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point for the MSR MCP Server.
 *
 * <p>Startup sequence:
 * <ol>
 *   <li>Verify that the current directory is a git repo (fail-fast otherwise)
 *   <li>Create {@code .msr/} directory and open the SQLite database
 *   <li>Index git history if the DB is empty
 *   <li>Register all four MCP tools
 *   <li>Start STDIO transport loop (blocks until client disconnects)
 * </ol>
 */
public final class Main {

    private static final String VERSION = "1.0.0-SNAPSHOT";
    private static final Path MSR_DIR   = Path.of(".msr");
    private static final Path DB_PATH   = MSR_DIR.resolve("msr.db");

    public static void main(String[] args) throws Exception {
        // ── [1] Verify git repo ────────────────────────────────────────────
        Path repoDir = Path.of(".").toAbsolutePath().normalize();
        if (!Files.exists(repoDir.resolve(".git"))) {
            System.err.println("ERROR: not a git repository: " + repoDir);
            System.exit(1);
        }

        // ── [2] Create .msr/ and open DB ──────────────────────────────────
        Files.createDirectories(MSR_DIR);
        Database db = Database.open(DB_PATH);

        CommitDao       commitDao       = db.attach(CommitDao.class);
        FileChangeDao   fileChangeDao   = db.attach(FileChangeDao.class);
        FileMetricsDao  fileMetricsDao  = db.attach(FileMetricsDao.class);
        FileCouplingDao fileCouplingDao = db.attach(FileCouplingDao.class);

        // ── [3] Index if empty ────────────────────────────────────────────
        if (commitDao.count() == 0) {
            System.err.println("MSR: no index found — running full index...");
            IndexResult r = Indexer.runFull(repoDir, db);
            if ("error".equals(r.status())) {
                System.err.println("MSR: indexing failed: " + r.errorMessage());
            } else {
                System.err.printf("MSR: indexed %d commits, %d files in %d ms%n",
                        r.commitsProcessed(), r.filesIndexed(), r.durationMs());
            }
        }

        // ── [4] Build MCP server ──────────────────────────────────────────
        var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("msr-mcp", VERSION)
                .capabilities(ServerCapabilities.builder()
                        .tools(false)   // enable tools; false = no list-changed notifications
                        .build())
                .tools(ToolRegistry.buildSpecs(
                        commitDao, fileChangeDao, fileMetricsDao, fileCouplingDao, repoDir, db))
                .build();

        // ── [5] Serve until client disconnects ───────────────────────────
        // StdioServerTransportProvider runs non-daemon threads that keep the JVM alive
        // until stdin closes (client disconnects). No explicit blocking needed.
        System.err.println("MSR MCP Server ready (STDIO).");
    }
}
