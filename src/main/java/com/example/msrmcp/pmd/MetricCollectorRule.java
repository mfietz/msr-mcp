package com.example.msrmcp.pmd;

import net.sourceforge.pmd.lang.java.ast.*;
import net.sourceforge.pmd.lang.java.metrics.JavaMetrics;
import net.sourceforge.pmd.lang.java.rule.AbstractJavaRule;
import net.sourceforge.pmd.lang.metrics.MetricsUtil;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * PMD 7 rule that visits every method/constructor declaration and collects
 * the maximum cyclomatic complexity and cognitive complexity per source file.
 * File-level LOC is captured from the compilation-unit end line.
 *
 * <p>Call {@link #getMaxCycloPerFile()}, {@link #getMaxCognitivePerFile()},
 * and {@link #getLocPerFile()} <em>after</em> {@code performAnalysis()} returns.
 */
public final class MetricCollectorRule extends AbstractJavaRule {

    private final Map<String, Integer> maxCycloPerFile     = new HashMap<>();
    private final Map<String, Integer> maxCognitivePerFile = new HashMap<>();
    private final Map<String, Integer> locPerFile          = new HashMap<>();

    public MetricCollectorRule() {
        setName("MetricCollector");
        setMessage("MSR metric collector — not a real violation");
    }

    // ── Compilation unit: record LOC ────────────────────────────────────────

    @Override
    public Object visit(ASTCompilationUnit node, Object data) {
        String file = fileOf(node);
        locPerFile.put(file, node.getEndLine());
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
        } catch (Exception ignored) {
            // -1 sentinel retained by absence from the map
        }
        try {
            Integer cog = MetricsUtil.computeMetric(JavaMetrics.COGNITIVE_COMPLEXITY, node);
            if (cog != null) maxCognitivePerFile.merge(file, cog, Math::max);
        } catch (Exception ignored) {}
    }

    private static String fileOf(ASTCompilationUnit root) {
        return root.getTextDocument().getFileId().getAbsolutePath();
    }

    public Map<String, Integer> getMaxCycloPerFile() {
        return Collections.unmodifiableMap(maxCycloPerFile);
    }

    public Map<String, Integer> getMaxCognitivePerFile() {
        return Collections.unmodifiableMap(maxCognitivePerFile);
    }

    public Map<String, Integer> getLocPerFile() {
        return Collections.unmodifiableMap(locPerFile);
    }
}
