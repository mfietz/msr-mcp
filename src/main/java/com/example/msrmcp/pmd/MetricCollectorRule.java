package com.example.msrmcp.pmd;

import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.java.ast.*;
import net.sourceforge.pmd.lang.java.metrics.JavaMetrics;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRule;
import net.sourceforge.pmd.lang.metrics.MetricsUtil;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PMD 7 rule that visits every method/constructor declaration and collects
 * the maximum cyclomatic complexity and cognitive complexity per source file.
 * File-level LOC is captured from the compilation-unit end line.
 *
 * <p>PMD 7 clones rule instances via reflection for each analysis thread. To share
 * results across all clones, the result maps are static. Call {@link #reset()} before
 * each {@code performAnalysis()} run, then read the static getters afterwards.
 */
public final class MetricCollectorRule extends AbstractJavaRule {

    // Static so that all PMD-created clones write into the same maps.
    private static final ConcurrentHashMap<String, Integer> maxCycloPerFile     = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> maxCognitivePerFile = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> locPerFile          = new ConcurrentHashMap<>();

    public MetricCollectorRule() {
        setName("MetricCollector");
        setMessage("MSR metric collector — not a real violation");
        // PMD 7 requires language to be set explicitly; AbstractJavaRule no longer
        // sets it implicitly (was deprecated in PMD 6, removed in PMD 7).
        setLanguage(LanguageRegistry.PMD.getLanguageByFullName("Java"));
    }

    /** Must be called before each {@code PmdAnalysis.performAnalysis()} run. */
    public static void reset() {
        maxCycloPerFile.clear();
        maxCognitivePerFile.clear();
        locPerFile.clear();
    }

    // ── Compilation unit: record LOC ────────────────────────────────────────

    @Override
    public Object visit(ASTCompilationUnit node, Object data) {
        locPerFile.put(fileOf(node), node.getEndLine());
        return super.visit(node, data);
    }

    // ── Methods ─────────────────────────────────────────────────────────────

    @Override
    public Object visit(ASTMethodDeclaration node, Object data) {
        collectCallable(node);
        return super.visit(node, data);
    }

    @Override
    public Object visit(ASTConstructorDeclaration node, Object data) {
        collectCallable(node);
        return super.visit(node, data);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Both ASTMethodDeclaration and ASTConstructorDeclaration extend
     * AbstractExecutableDeclaration which implements ASTExecutableDeclaration,
     * which is what JavaMetrics.CYCLO / COGNITIVE_COMPLEXITY operate on.
     */
    private void collectCallable(ASTExecutableDeclaration node) {
        String file = fileOf(node.getRoot());
        try {
            Integer cyclo = MetricsUtil.computeMetric(JavaMetrics.CYCLO, node);
            if (cyclo != null) maxCycloPerFile.merge(file, cyclo, Math::max);
        } catch (Exception ignored) {}
        try {
            Integer cog = MetricsUtil.computeMetric(JavaMetrics.COGNITIVE_COMPLEXITY, node);
            if (cog != null) maxCognitivePerFile.merge(file, cog, Math::max);
        } catch (Exception ignored) {}
    }

    private static String fileOf(ASTCompilationUnit root) {
        return root.getTextDocument().getFileId().getAbsolutePath();
    }

    public static Map<String, Integer> getMaxCycloPerFile() {
        return Collections.unmodifiableMap(maxCycloPerFile);
    }

    public static Map<String, Integer> getMaxCognitivePerFile() {
        return Collections.unmodifiableMap(maxCognitivePerFile);
    }

    public static Map<String, Integer> getLocPerFile() {
        return Collections.unmodifiableMap(locPerFile);
    }
}
