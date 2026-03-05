package de.mfietz.msrmcp.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts a JIRA-style ticket slug from the first line of a commit message. */
public final class JiraSlugExtractor {

    private static final Pattern PATTERN = Pattern.compile("^([A-Z]{2,4}-\\d+)");

    private JiraSlugExtractor() {}

    /** @return the slug (e.g. "ABC-123") or {@code null} if not present. */
    public static String extract(String firstLine) {
        if (firstLine == null) return null;
        Matcher m = PATTERN.matcher(firstLine.strip());
        return m.find() ? m.group(1) : null;
    }
}
