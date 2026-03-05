package com.example.msrmcp.index;

import com.example.msrmcp.db.CommitDao;
import com.example.msrmcp.db.FileDao;
import com.example.msrmcp.db.FileCouplingDao;
import com.example.msrmcp.db.FileChangeDao;
import com.example.msrmcp.db.FileChangeDao.FileChangeIdRecord;
import com.example.msrmcp.db.FileCouplingDao.FileCouplingIdRecord;
import com.example.msrmcp.model.CommitRecord;
import com.example.msrmcp.util.JiraSlugExtractor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/**
 * Walks the default branch (main → master → HEAD) of a git repository,
 * inserts commits and file-change records into the DB, and accumulates
 * in-memory co-change data that is flushed to file_coupling.
 */
final class GitWalker {

    private static final int BATCH_SIZE = 500;
    private static final Logger LOG = Logger.getLogger(GitWalker.class.getName());
    /** Commits touching more files than this are excluded from coupling (bulk refactors, merges). */
    static final int MAX_PATHS_FOR_COUPLING = 50;

    private final Path repoDir;
    private final CommitDao commitDao;
    private final FileChangeDao fileChangeDao;
    private final FileCouplingDao fileCouplingDao;
    private final FileDao fileDao;

    GitWalker(Path repoDir, CommitDao commitDao,
              FileChangeDao fileChangeDao, FileCouplingDao fileCouplingDao,
              FileDao fileDao) {
        this.repoDir = repoDir;
        this.commitDao = commitDao;
        this.fileChangeDao = fileChangeDao;
        this.fileCouplingDao = fileCouplingDao;
        this.fileDao = fileDao;
    }

    record WalkResult(int commitsProcessed, Set<String> changedPaths) {}

    /**
     * Full walk — processes every commit reachable from HEAD.
     * Caller is responsible for clearing {@code file_coupling} beforehand.
     */
    WalkResult walk() throws IOException {
        return walk(null);
    }

    /**
     * Incremental walk — only processes commits that are not ancestors of
     * {@code stopAtHash}. Pass {@code null} for a full walk.
     *
     * <p>Uses JGit {@code markUninteresting} so the RevWalk stops naturally
     * at the already-indexed boundary without scanning the entire history.
     */
    WalkResult walk(String stopAtHash) throws IOException {
        try (Git git = Git.open(repoDir.toFile());
             RevWalk revWalk = new RevWalk(git.getRepository())) {

            Repository repo = git.getRepository();

            ObjectId headId = resolveDefaultBranch(repo);
            if (headId == null) return new WalkResult(0, Set.of());

            revWalk.markStart(revWalk.parseCommit(headId));

            if (stopAtHash != null) {
                ObjectId stopId = repo.resolve(stopAtHash);
                if (stopId != null) {
                    revWalk.markUninteresting(revWalk.parseCommit(stopId));
                }
            }

            // Walk oldest-first so rename pairs are collected in chronological order
            revWalk.sort(RevSort.REVERSE);

            int nThreads = Runtime.getRuntime().availableProcessors();
            ExecutorService pool = Executors.newFixedThreadPool(nThreads);
            List<DiffFormatter> formatters = Collections.synchronizedList(new ArrayList<>());
            ThreadLocal<DiffFormatter> dfLocal = ThreadLocal.withInitial(() -> {
                DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
                df.setRepository(repo);
                df.setDiffComparator(RawTextComparator.DEFAULT);
                df.setDetectRenames(true);
                formatters.add(df);
                return df;
            });

            Map<String, int[]> coChanges    = new HashMap<>();
            Map<String, int[]> totalChanges = new HashMap<>();
            Set<String> allChangedPaths     = new HashSet<>();
            List<String[]> renames          = new ArrayList<>();

            List<CommitRecord> commitBatch = new ArrayList<>(BATCH_SIZE);
            List<ChangeEntry>  changeBatch  = new ArrayList<>(BATCH_SIZE * 4);
            int processed = 0;

            List<RevCommit> window = new ArrayList<>(BATCH_SIZE);
            try {
                for (RevCommit commit : revWalk) {
                    window.add(commit);
                    if (window.size() == BATCH_SIZE) {
                        processWindow(window, repo, dfLocal, pool, commitBatch, changeBatch,
                                      coChanges, totalChanges, renames, allChangedPaths);
                        processed += window.size();
                        window.clear();
                        System.err.printf("MSR:   %,d commits processed...%n", processed);
                    }
                }
                if (!window.isEmpty()) {
                    processWindow(window, repo, dfLocal, pool, commitBatch, changeBatch,
                                  coChanges, totalChanges, renames, allChangedPaths);
                    processed += window.size();
                }
            } finally {
                pool.shutdownNow();
                formatters.forEach(DiffFormatter::close);
            }
            flushCoupling(coChanges, totalChanges);
            mergeRenames(renames);

            return new WalkResult(processed, allChangedPaths);
        }
    }

    private static ObjectId resolveDefaultBranch(Repository repo) throws IOException {
        ObjectId id = repo.resolve("refs/heads/main");
        if (id == null) id = repo.resolve("refs/heads/master");
        if (id == null) id = repo.resolve(Constants.HEAD);
        return id;
    }

    private static List<DiffEntry> getDiffs(Repository repo, RevCommit commit,
                                             DiffFormatter df) throws IOException {
        if (commit.getParentCount() == 0) {
            try (ObjectReader reader = repo.newObjectReader()) {
                CanonicalTreeParser newTree = new CanonicalTreeParser();
                newTree.reset(reader, commit.getTree().getId());
                return df.scan(new EmptyTreeIterator(), newTree);
            }
        }
        return df.scan(commit.getParent(0).getTree(), commit.getTree());
    }

    private record ChangeEntry(String hash, String path, int linesAdded, int linesDeleted) {}

    private record CommitDiff(RevCommit commit, List<EntryData> entries) {}

    private record EntryData(String path, String oldPath, boolean isRename,
                             int linesAdded, int linesDeleted) {}

    private static void accumulateCoChanges(List<String> paths, Map<String, int[]> coChanges) {
        int n = paths.size();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                String a = paths.get(i);
                String b = paths.get(j);
                if (a.compareTo(b) > 0) { String tmp = a; a = b; b = tmp; }
                coChanges.computeIfAbsent(a + "\0" + b, k -> new int[1])[0]++;
            }
        }
    }

    private void flush(List<CommitRecord> commitBatch, List<ChangeEntry> changeBatch) {
        if (!commitBatch.isEmpty()) commitDao.insertBatch(commitBatch);
        if (!changeBatch.isEmpty()) {
            // Resolve commit hashes → IDs
            Map<String, Long> hashToId = resolveHashes(
                    changeBatch.stream().map(ChangeEntry::hash).distinct().toList());

            // Resolve file paths → IDs
            Map<String, Long> pathToId = resolvePaths(
                    changeBatch.stream().map(ChangeEntry::path).distinct().toList());

            List<FileChangeIdRecord> idRecords = new ArrayList<>(changeBatch.size());
            for (ChangeEntry e : changeBatch) {
                idRecords.add(new FileChangeIdRecord(hashToId.get(e.hash()), pathToId.get(e.path()),
                        e.linesAdded(), e.linesDeleted()));
            }
            fileChangeDao.insertBatch(idRecords);
        }
        commitBatch.clear();
        changeBatch.clear();
    }

    private void flushCoupling(Map<String, int[]> coChanges, Map<String, int[]> totalChanges) {
        if (coChanges.isEmpty()) return;

        // Collect all unique paths from coupling data
        Set<String> allPaths = new HashSet<>();
        for (var entry : coChanges.entrySet()) {
            String[] parts = entry.getKey().split("\0", 2);
            allPaths.add(parts[0]);
            allPaths.add(parts[1]);
        }
        Map<String, Long> pathToId = resolvePaths(new ArrayList<>(allPaths));

        List<FileCouplingIdRecord> records = new ArrayList<>(coChanges.size());
        for (var entry : coChanges.entrySet()) {
            String[] parts = entry.getKey().split("\0", 2);
            String a = parts[0], b = parts[1];
            int co = entry.getValue()[0];
            int ta = totalChanges.getOrDefault(a, new int[]{0})[0];
            int tb = totalChanges.getOrDefault(b, new int[]{0})[0];

            long idA = pathToId.get(a);
            long idB = pathToId.get(b);
            // Ensure fileAId < fileBId; swap totalChanges accordingly
            if (idA > idB) {
                records.add(new FileCouplingIdRecord(idB, idA, co, tb, ta));
            } else {
                records.add(new FileCouplingIdRecord(idA, idB, co, ta, tb));
            }
        }

        int chunkSize = 500;
        for (int i = 0; i < records.size(); i += chunkSize) {
            fileCouplingDao.upsertBatch(records.subList(i, Math.min(i + chunkSize, records.size())));
        }
    }

    /**
     * Renames paths in the in-memory co-change and total-change maps so that
     * coupling data accumulated for the old name is credited to the new name.
     * Called immediately when a RENAME diff entry is encountered during the walk.
     */
    private static void applyRenameInMemory(String oldPath, String newPath,
                                             Map<String, int[]> coChanges,
                                             Map<String, int[]> totalChanges) {
        int[] tc = totalChanges.remove(oldPath);
        if (tc != null) totalChanges.merge(newPath, tc, (x, y) -> { x[0] += y[0]; return x; });

        Map<String, int[]> updates = new HashMap<>();
        Iterator<Map.Entry<String, int[]>> it = coChanges.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, int[]> e = it.next();
            String[] parts = e.getKey().split("\0", 2);
            String a = parts[0], b = parts[1];
            if (!a.equals(oldPath) && !b.equals(oldPath)) continue;
            it.remove();
            if (a.equals(oldPath)) a = newPath;
            if (b.equals(oldPath)) b = newPath;
            if (a.compareTo(b) > 0) { String tmp = a; a = b; b = tmp; }
            updates.merge(a + "\0" + b, e.getValue(), (x, y) -> { x[0] += y[0]; return x; });
        }
        coChanges.putAll(updates);
    }

    /**
     * Computes the diff for a single commit and returns serialisable data only.
     * Safe to call from any thread because the caller supplies its own {@code df}.
     * Exceptions inside individual entry processing are swallowed (best-effort).
     */
    private static CommitDiff computeCommitDiff(Repository repo, RevCommit commit,
                                                 DiffFormatter df) {
        List<EntryData> entries = new ArrayList<>();
        try {
            for (DiffEntry entry : getDiffs(repo, commit, df)) {
                boolean isDelete = entry.getChangeType() == DiffEntry.ChangeType.DELETE;
                boolean isRename = entry.getChangeType() == DiffEntry.ChangeType.RENAME;
                String path    = isDelete ? entry.getOldPath() : entry.getNewPath();
                String oldPath = isRename ? entry.getOldPath() : null;
                int linesAdded = 0, linesDeleted = 0;
                try {
                    for (Edit edit : df.toFileHeader(entry).toEditList()) {
                        linesAdded   += edit.getEndB() - edit.getBeginB();
                        linesDeleted += edit.getEndA() - edit.getBeginA();
                    }
                } catch (Exception ignored) {}
                entries.add(new EntryData(path, oldPath, isRename, linesAdded, linesDeleted));
            }
        } catch (Exception ignored) {}
        return new CommitDiff(commit, entries);
    }

    /**
     * Two-phase batch processor.
     *
     * <p>Phase 1: submits one diff-computation task per commit to {@code pool};
     * each task uses a thread-local {@code DiffFormatter} (JGit objects are not
     * thread-safe).
     *
     * <p>Phase 2: retrieves futures in submission order (= chronological order)
     * and updates all in-memory maps sequentially — same semantics as the old loop.
     */
    private void processWindow(
            List<RevCommit> window, Repository repo,
            ThreadLocal<DiffFormatter> dfLocal, ExecutorService pool,
            List<CommitRecord> commitBatch, List<ChangeEntry> changeBatch,
            Map<String, int[]> coChanges, Map<String, int[]> totalChanges,
            List<String[]> renames, Set<String> allChangedPaths) {

        // Phase 1 — parallel diff computation
        List<Future<CommitDiff>> futures = new ArrayList<>(window.size());
        for (RevCommit commit : window) {
            futures.add(pool.submit(() -> computeCommitDiff(repo, commit, dfLocal.get())));
        }

        // Phase 2 — sequential map update in chronological order
        for (Future<CommitDiff> future : futures) {
            CommitDiff cd;
            try {
                cd = future.get();
            } catch (InterruptedException | ExecutionException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }

            RevCommit commit = cd.commit();
            String hash        = commit.getName();
            long authorDate    = commit.getAuthorIdent().getWhen().getTime();
            String firstLine   = commit.getShortMessage();
            String jiraSlug    = JiraSlugExtractor.extract(firstLine);
            String authorEmail = commit.getAuthorIdent().getEmailAddress();
            String authorName  = commit.getAuthorIdent().getName();

            commitBatch.add(new CommitRecord(hash, authorDate, firstLine, jiraSlug, authorEmail, authorName));

            List<String> changedPaths = new ArrayList<>(cd.entries().size());
            for (EntryData e : cd.entries()) {
                changeBatch.add(new ChangeEntry(hash, e.path(), e.linesAdded(), e.linesDeleted()));
                changedPaths.add(e.path());
                totalChanges.computeIfAbsent(e.path(), k -> new int[1])[0]++;
                allChangedPaths.add(e.path());
                if (e.isRename()) {
                    renames.add(new String[]{e.oldPath(), e.path()});
                    applyRenameInMemory(e.oldPath(), e.path(), coChanges, totalChanges);
                }
            }

            if (changedPaths.size() <= MAX_PATHS_FOR_COUPLING) {
                accumulateCoChanges(changedPaths, coChanges);
            }
        }

        flush(commitBatch, changeBatch);
    }

    /**
     * After all flushes, merges file history from old paths into new paths for each
     * rename pair. Applied in chronological order so rename chains (A→B→C) resolve
     * correctly. Each merge: re-points file_changes from oldId→newId, then removes
     * the old files row.
     */
    private void mergeRenames(List<String[]> renames) {
        if (renames.isEmpty()) return;
        for (String[] pair : renames) {
            String oldPath = pair[0], newPath = pair[1];
            List<Long> oldIds = fileDao.findIdByPath(oldPath);
            if (oldIds.isEmpty()) continue; // old path was never tracked
            long oldId = oldIds.getFirst();
            List<Long> newIds = fileDao.findIdByPath(newPath);
            if (newIds.isEmpty()) {
                // No post-rename commits for this path yet — just rename in place
                fileDao.updatePath(oldPath, newPath);
            } else {
                long newId = newIds.getFirst();
                fileChangeDao.updateFileId(oldId, newId);
                fileDao.deleteById(oldId);
                LOG.fine(() -> "Merged rename %s → %s (file_id %d → %d)".formatted(oldPath, newPath, oldId, newId));
            }
        }
    }

    /**
     * Looks up commit hashes and returns a hash→commitId map.
     * Commits must already be inserted. Chunks to respect SQLite limits.
     */
    private Map<String, Long> resolveHashes(List<String> hashes) {
        if (hashes.isEmpty()) return Map.of();

        int chunkSize = 999;
        Map<String, Long> result = new HashMap<>();
        for (int i = 0; i < hashes.size(); i += chunkSize) {
            List<String> chunk = hashes.subList(i, Math.min(i + chunkSize, hashes.size()));
            for (CommitDao.CommitIdRecord r : commitDao.findByHashes(chunk)) {
                result.put(r.hash(), r.commitId());
            }
        }
        return result;
    }

    /**
     * Inserts paths into the files table (INSERT OR IGNORE) and returns
     * a path→fileId map. Chunks into groups of 999 to respect SQLite limits.
     */
    private Map<String, Long> resolvePaths(List<String> paths) {
        if (paths.isEmpty()) return Map.of();

        // Insert all paths (ignores existing)
        int chunkSize = 999;
        for (int i = 0; i < paths.size(); i += chunkSize) {
            fileDao.insertBatch(paths.subList(i, Math.min(i + chunkSize, paths.size())));
        }

        // Fetch IDs
        Map<String, Long> result = new HashMap<>();
        for (int i = 0; i < paths.size(); i += chunkSize) {
            List<String> chunk = paths.subList(i, Math.min(i + chunkSize, paths.size()));
            for (FileDao.FileRecord r : fileDao.findByPaths(chunk)) {
                result.put(r.path(), r.fileId());
            }
        }
        return result;
    }
}
