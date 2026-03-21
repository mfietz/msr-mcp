package de.mfietz.msrmcp.tool;

import static de.mfietz.msrmcp.tool.GetHotspotsTool.*;

import de.mfietz.msrmcp.db.FileCouplingDao;
import de.mfietz.msrmcp.db.FileCouplingDao.CouplingRow;
import de.mfietz.msrmcp.index.IndexTracker;
import de.mfietz.msrmcp.model.ClusterEdge;
import de.mfietz.msrmcp.model.CouplingClusterResult;
import io.modelcontextprotocol.spec.McpSchema.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP tool: {@code get_coupling_clusters}
 *
 * <p>Two modes:
 *
 * <ul>
 *   <li>Global scan (no {@code filePath}): Union-Find over all edges ≥ minCoupling from the
 *       pre-aggregated {@code file_coupling} table.
 *   <li>Single-file lookup ({@code filePath} set): recursive CTE traverses only the target file's
 *       connected component — much faster for targeted queries.
 * </ul>
 *
 * <p>Uses MAX normalization ({@code co_changes / MAX(total_a, total_b)}) so hub files that
 * co-change with many unrelated files are excluded from clusters naturally.
 */
public final class GetCouplingClustersTool {

    static final String NAME = "get_coupling_clusters";
    private static final JsonMapper MAPPER = JsonMapper.shared();
    private static final int EDGE_CAP = 10_000;

    private final FileCouplingDao fileCouplingDao;
    private final IndexTracker tracker;

    public GetCouplingClustersTool(FileCouplingDao fileCouplingDao, IndexTracker tracker) {
        this.fileCouplingDao = fileCouplingDao;
        this.tracker = tracker;
    }

    public CallToolResult handle(Map<String, Object> args) {
        if (!tracker.isReady()) {
            return error(
                    "Index not ready (status: "
                            + tracker.state().name().toLowerCase()
                            + "). Call get_index_status to check progress.");
        }
        try {
            double minCoupling = doubleArg(args, "minCoupling", 0.3);
            int minClusterSize = intArg(args, "minClusterSize", 2);
            int topN = intArg(args, "topN", 20);
            Long sinceEpochMs = longArg(args, "sinceEpochMs");
            String filePath = stringArg(args, "filePath", null);

            if (filePath != null && !filePath.isBlank()) {
                return handleFileMode(filePath, minCoupling, sinceEpochMs);
            }
            return handleGlobalMode(minCoupling, minClusterSize, topN, sinceEpochMs);
        } catch (Exception e) {
            return error("get_coupling_clusters failed: " + e.getMessage());
        }
    }

    private CallToolResult handleGlobalMode(
            double minCoupling, int minClusterSize, int topN, Long sinceEpochMs) throws Exception {
        List<CouplingRow> rows =
                sinceEpochMs != null
                        ? fileCouplingDao.findEdgesForClusteringSince(sinceEpochMs, minCoupling)
                        : fileCouplingDao.findEdgesForClustering(minCoupling);

        if (rows.size() == EDGE_CAP) {
            return error(
                    "Edge cap reached (10 000 edges). Increase minCoupling to reduce graph size.");
        }

        UnionFind uf = new UnionFind();
        for (CouplingRow row : rows) {
            uf.union(row.fileA(), row.fileB());
        }

        Map<String, List<CouplingRow>> byRoot = new HashMap<>();
        for (CouplingRow row : rows) {
            byRoot.computeIfAbsent(uf.find(row.fileA()), k -> new ArrayList<>()).add(row);
        }

        List<CouplingClusterResult> clusters = new ArrayList<>();
        for (List<CouplingRow> clusterEdges : byRoot.values()) {
            Set<String> members = new HashSet<>();
            for (CouplingRow e : clusterEdges) {
                members.add(e.fileA());
                members.add(e.fileB());
            }
            if (members.size() < minClusterSize) continue;
            clusters.add(buildCluster(0, clusterEdges));
        }

        clusters.sort(Comparator.comparingDouble(CouplingClusterResult::avgCoupling).reversed());

        List<CouplingClusterResult> result = new ArrayList<>();
        for (int i = 0; i < Math.min(clusters.size(), topN); i++) {
            CouplingClusterResult c = clusters.get(i);
            result.add(new CouplingClusterResult(i + 1, c.files(), c.edges(), c.avgCoupling()));
        }
        return ok(MAPPER.writeValueAsString(result));
    }

    private CallToolResult handleFileMode(String filePath, double minCoupling, Long sinceEpochMs)
            throws Exception {
        List<CouplingRow> rows =
                sinceEpochMs != null
                        ? fileCouplingDao.findClusterForFileSince(
                                filePath, sinceEpochMs, minCoupling)
                        : fileCouplingDao.findClusterForFile(filePath, minCoupling);
        if (rows.isEmpty()) return ok("[]");
        return ok(MAPPER.writeValueAsString(List.of(buildCluster(1, rows))));
    }

    private static CouplingClusterResult buildCluster(int index, List<CouplingRow> edges) {
        Set<String> files = new TreeSet<>();
        List<ClusterEdge> edgeList = new ArrayList<>();
        double sumRatio = 0.0;
        for (CouplingRow row : edges) {
            files.add(row.fileA());
            files.add(row.fileB());
            edgeList.add(
                    new ClusterEdge(
                            row.fileA(), row.fileB(), row.coChanges(), row.couplingRatio()));
            sumRatio += row.couplingRatio();
        }
        double avgCoupling = edges.isEmpty() ? 0.0 : sumRatio / edges.size();
        return new CouplingClusterResult(index, List.copyOf(files), edgeList, avgCoupling);
    }

    static Tool toolSpec() {
        return Tool.builder()
                .name(NAME)
                .description(
                        """
                        Identifies groups of files that frequently change together (co-change clusters).
                        Uses bidirectional coupling (co_changes / MAX(total_a, total_b)) to exclude
                        hub files that are changed alongside everything.
                        Without filePath: returns all clusters sorted by average coupling (global scan).
                        With filePath: returns the single cluster containing that file (fast recursive
                        graph traversal — preferred for targeted lookups).
                        """)
                .inputSchema(ToolSchemas.couplingClusters())
                .build();
    }

    private static final class UnionFind {

        private final Map<String, String> parent = new HashMap<>();

        String find(String x) {
            parent.putIfAbsent(x, x);
            if (!parent.get(x).equals(x)) {
                parent.put(x, find(parent.get(x))); // path compression
            }
            return parent.get(x);
        }

        void union(String x, String y) {
            String px = find(x), py = find(y);
            if (!px.equals(py)) parent.put(px, py);
        }
    }
}
