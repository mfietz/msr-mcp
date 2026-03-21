package de.mfietz.msrmcp.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import de.mfietz.msrmcp.db.Database;
import de.mfietz.msrmcp.db.FileCouplingDao;
import de.mfietz.msrmcp.helper.TestRepoBuilder;
import de.mfietz.msrmcp.index.IndexTracker;
import de.mfietz.msrmcp.index.Indexer;
import de.mfietz.msrmcp.tool.GetCouplingClustersTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.*;

/**
 * Acceptance tests for get_coupling_clusters.
 *
 * <p>Test repo: auth/Login+Auth always co-change (3 commits); repo/UserRepo+OrderRepo always
 * co-change (3 different commits). No cross-cluster co-changes. Expected: two distinct clusters
 * with MAX-normalized couplingRatio=1.0 each.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetCouplingClustersAcceptanceTest {

    Path repoDir;
    Database db;
    GetCouplingClustersTool tool;
    IndexTracker tracker;

    @BeforeAll
    void setUp() throws Exception {
        repoDir =
                new TestRepoBuilder()
                        .commit(
                                "init auth",
                                Map.of(
                                        "auth/Login.java", "class Login {}",
                                        "auth/Auth.java", "class Auth {}"))
                        .commit(
                                "feat auth",
                                Map.of(
                                        "auth/Login.java", "class Login { void v(){} }",
                                        "auth/Auth.java", "class Auth { void v(){} }"))
                        .commit(
                                "fix auth",
                                Map.of(
                                        "auth/Login.java", "class Login { void v2(){} }",
                                        "auth/Auth.java", "class Auth { void v2(){} }"))
                        .commit(
                                "init repo",
                                Map.of(
                                        "repo/UserRepo.java", "class UserRepo {}",
                                        "repo/OrderRepo.java", "class OrderRepo {}"))
                        .commit(
                                "feat repo",
                                Map.of(
                                        "repo/UserRepo.java", "class UserRepo { void v(){} }",
                                        "repo/OrderRepo.java", "class OrderRepo { void v(){} }"))
                        .commit(
                                "fix repo",
                                Map.of(
                                        "repo/UserRepo.java", "class UserRepo { void v2(){} }",
                                        "repo/OrderRepo.java", "class OrderRepo { void v2(){} }"))
                        .build();

        Files.createDirectories(repoDir.resolve(".msr"));
        db = Database.open(repoDir.resolve(".msr/msr.db"));
        Indexer.runFull(repoDir, db);

        tracker = new IndexTracker();
        tracker.markReady(0);
        tool = new GetCouplingClustersTool(db.attach(FileCouplingDao.class), tracker);
    }

    @AfterAll
    void tearDown() throws Exception {
        TestRepoBuilder.deleteRecursively(repoDir);
    }

    // --- Mode 1: global scan ---

    @Test
    void globalMode_returnsTwoClusters() {
        String json = text(tool.handle(Map.of("minClusterSize", 2)));
        assertThat(json).contains("\"clusterIndex\":1");
        assertThat(json).contains("\"clusterIndex\":2");
        assertThat(json).contains("auth/Login.java");
        assertThat(json).contains("auth/Auth.java");
        assertThat(json).contains("repo/UserRepo.java");
        assertThat(json).contains("repo/OrderRepo.java");
    }

    @Test
    void globalMode_eachClusterHasEdgesAndFullCoupling() {
        String json = text(tool.handle(Map.of("minClusterSize", 2)));
        // Each pair co-changed in 3 commits with ratio 1.0 (MAX normalization)
        assertThat(json).contains("\"coChanges\":3");
        assertThat(json).contains("\"couplingRatio\":1.0");
        assertThat(json).contains("\"avgCoupling\":1.0");
    }

    @Test
    void globalMode_minClusterSizeDefault_isThree() {
        // Default minClusterSize is 3; all test clusters have 2 files → excluded by default
        String json = text(tool.handle(Map.of()));
        assertThat(json).isEqualTo("[]");
    }

    @Test
    void globalMode_minClusterSizeTwo_returnsClusters() {
        // Explicitly lower threshold to 2 to get results
        String json = text(tool.handle(Map.of("minClusterSize", 2)));
        assertThat(json).contains("\"clusterIndex\":1");
        assertThat(json).contains("auth/Login.java");
    }

    @Test
    void globalMode_maxClusterSize_excludesLargeClusters() {
        // maxClusterSize=1 means no cluster with 2+ files is returned
        String json = text(tool.handle(Map.of("minClusterSize", 2, "maxClusterSize", 1)));
        assertThat(json).isEqualTo("[]");
    }

    @Test
    void globalMode_pathFilter_returnsMatchingClustersOnly() {
        // Filter to auth/ path — only auth cluster should appear
        String json = text(tool.handle(Map.of("minClusterSize", 2, "pathFilter", "auth/%")));
        assertThat(json).contains("auth/Login.java");
        assertThat(json).contains("auth/Auth.java");
        assertThat(json).doesNotContain("UserRepo");
        assertThat(json).doesNotContain("OrderRepo");
    }

    @Test
    void globalMode_pathFilter_noMatch_returnsEmpty() {
        String json = text(tool.handle(Map.of("minClusterSize", 2, "pathFilter", "service/%")));
        assertThat(json).isEqualTo("[]");
    }

    @Test
    void fileMode_maxClusterSize_excludesLargeCluster() {
        // The auth cluster has 2 files; maxClusterSize=1 excludes it
        String json = text(tool.handle(Map.of("filePath", "auth/Login.java", "maxClusterSize", 1)));
        assertThat(json).isEqualTo("[]");
    }

    @Test
    void globalMode_highMinCoupling_excludesAll() {
        // coupling_ratio is 1.0; threshold > 1.0 returns nothing
        String json = text(tool.handle(Map.of("minCoupling", 1.1)));
        assertThat(json).isEqualTo("[]");
    }

    // --- Mode 2: single-file lookup ---

    @Test
    void fileMode_returnsCorrectCluster() {
        String json = text(tool.handle(Map.of("filePath", "auth/Login.java")));
        assertThat(json).contains("auth/Login.java");
        assertThat(json).contains("auth/Auth.java");
        // Repo files must NOT appear
        assertThat(json).doesNotContain("UserRepo");
        assertThat(json).contains("\"clusterIndex\":1");
    }

    @Test
    void fileMode_unknownFile_returnsEmptyArray() {
        String json = text(tool.handle(Map.of("filePath", "does/not/exist/Foo.java")));
        assertThat(json).isEqualTo("[]");
    }

    // --- Guard ---

    @Test
    void indexNotReady_returnsError() {
        GetCouplingClustersTool unready =
                new GetCouplingClustersTool(db.attach(FileCouplingDao.class), new IndexTracker());
        CallToolResult result = unready.handle(Map.of());
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("Index not ready");
    }

    // --- sinceEpochMs (CTE path) ---

    @Test
    void globalMode_sinceEpochMs_returnsSameClusters() {
        // epoch 0 includes all commits → same result as fast path
        String json = text(tool.handle(Map.of("sinceEpochMs", 0, "minClusterSize", 2)));
        assertThat(json).contains("auth/Login.java");
        assertThat(json).contains("repo/UserRepo.java");
    }

    @Test
    void fileMode_sinceEpochMs_returnsCorrectCluster() {
        String json = text(tool.handle(Map.of("filePath", "auth/Login.java", "sinceEpochMs", 0)));
        assertThat(json).contains("auth/Login.java");
        assertThat(json).contains("auth/Auth.java");
        assertThat(json).doesNotContain("UserRepo");
    }

    private static String text(CallToolResult r) {
        return ((TextContent) r.content().getFirst()).text();
    }
}
