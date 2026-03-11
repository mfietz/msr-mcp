package de.mfietz.msrmcp.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses a {@code .mailmap} file and resolves commit author identities to their canonical form.
 *
 * <p>Supported formats (per git-check-mailmap(1)):
 *
 * <pre>
 *   Proper Name &lt;commit@email&gt;
 *   &lt;proper@email&gt; &lt;commit@email&gt;
 *   Proper Name &lt;proper@email&gt; &lt;commit@email&gt;
 *   Proper Name &lt;proper@email&gt; Commit Name &lt;commit@email&gt;
 * </pre>
 *
 * <p>If the {@code .mailmap} file does not exist, {@link #resolve} returns the input unchanged.
 * Email matching is case-insensitive. First match wins.
 */
final class MailMap {

    record Identity(String name, String email) {}

    private record Entry(
            String canonicalName,
            String canonicalEmail,
            String commitName, // null = match any name
            String commitEmail) {}

    private final List<Entry> entries;

    private MailMap(List<Entry> entries) {
        this.entries = entries;
    }

    /** Loads {@code .mailmap} from the repo root. Returns an empty map if the file is absent. */
    static MailMap load(Path repoDir) throws IOException {
        Path file = repoDir.resolve(".mailmap");
        if (!Files.exists(file)) return new MailMap(List.of());

        List<Entry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Entry entry = parse(line);
            if (entry != null) entries.add(entry);
        }
        return new MailMap(entries);
    }

    /**
     * Returns the canonical identity for the given commit author. If no entry matches, returns the
     * input unchanged.
     */
    Identity resolve(String commitName, String commitEmail) {
        String lowerEmail = commitEmail.toLowerCase();
        for (Entry e : entries) {
            if (!e.commitEmail().equalsIgnoreCase(lowerEmail)) continue;
            if (e.commitName() != null && !e.commitName().equalsIgnoreCase(commitName)) continue;

            String resolvedName = e.canonicalName() != null ? e.canonicalName() : commitName;
            String resolvedEmail = e.canonicalEmail() != null ? e.canonicalEmail() : commitEmail;
            return new Identity(resolvedName, resolvedEmail);
        }
        return new Identity(commitName, commitEmail);
    }

    /**
     * Parses a single non-blank, non-comment line. Returns {@code null} if the line cannot be
     * parsed.
     *
     * <p>Strategy: scan for {@code <…>} tokens left-to-right; text between tokens is a name.
     */
    private static Entry parse(String line) {
        List<String> emails = new ArrayList<>();
        List<String> names = new ArrayList<>();

        int pos = 0;
        int len = line.length();
        while (pos < len) {
            int ltIdx = line.indexOf('<', pos);
            if (ltIdx < 0) break;
            int gtIdx = line.indexOf('>', ltIdx);
            if (gtIdx < 0) break;

            // Text before this '<' (trimmed) may be a name
            String before = line.substring(pos, ltIdx).strip();
            if (!before.isEmpty()) names.add(before);

            emails.add(line.substring(ltIdx + 1, gtIdx));
            pos = gtIdx + 1;
        }

        // Need at least one email (commit email is always the last)
        if (emails.isEmpty()) return null;

        String commitEmail = emails.getLast();

        if (emails.size() == 1) {
            // "Proper Name <commit@email>" or just "<commit@email>" — name-only override
            String canonicalName = names.isEmpty() ? null : names.getFirst();
            return new Entry(canonicalName, null, null, commitEmail);
        }

        // emails.size() >= 2: first email is canonical, last is commit
        String canonicalEmail = emails.getFirst();

        if (names.isEmpty()) {
            // "<proper@email> <commit@email>"
            return new Entry(null, canonicalEmail, null, commitEmail);
        }

        if (names.size() == 1) {
            // "Proper Name <proper@email> <commit@email>"
            return new Entry(names.getFirst(), canonicalEmail, null, commitEmail);
        }

        // "Proper Name <proper@email> Commit Name <commit@email>"
        return new Entry(names.getFirst(), canonicalEmail, names.getLast(), commitEmail);
    }
}
