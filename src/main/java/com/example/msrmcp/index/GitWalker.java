package com.example.msrmcp.index;

import com.example.msrmcp.db.CommitDao;
import com.example.msrmcp.db.FileCouplingDao;
import com.example.msrmcp.db.FileChangeDao;
import com.example.msrmcp.model.CommitRecord;
import com.example.msrmcp.model.FileCouplingRecord;
import com.example.msrmcp.model.FileChangeRecord;
import com.example.msrmcp.util.JiraSlugExtractor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Walks the default branch (main → master → HEAD) of a git repository,
 * inserts commits and file-change records into the DB, and accumulates
 * in-memory co-change data that is flushed to file_coupling.
 */
final class GitWalker {

    private static final int BATCH_SIZE = 500;

    private final Path repoDir;
    private final CommitDao commitDao;
    private final FileChangeDao fileChangeDao;
    private final FileCouplingDao fileCouplingDao;

    GitWalker(Path repoDir, CommitDao commitDao,
              FileChangeDao fileChangeDao, FileCouplingDao fileCouplingDao) {
        this.repoDir = repoDir;
        this.commitDao = commitDao;
        this.fileChangeDao = fileChangeDao;
        this.fileCouplingDao = fileCouplingDao;
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

            DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
            df.setRepository(repo);
            df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setDetectRenames(true);

            Map<String, int[]> coChanges   = new HashMap<>();
            Map<String, int[]> totalChanges = new HashMap<>();
            Set<String> allChangedPaths    = new HashSet<>();

            List<CommitRecord>     commitBatch = new ArrayList<>(BATCH_SIZE);
            List<FileChangeRecord> changeBatch = new ArrayList<>(BATCH_SIZE * 4);
            int processed = 0;

            for (RevCommit commit : revWalk) {
                String hash      = commit.getName();
                long authorDate  = commit.getAuthorIdent().getWhen().getTime();
                String firstLine = commit.getShortMessage();
                String jiraSlug  = JiraSlugExtractor.extract(firstLine);

                commitBatch.add(new CommitRecord(hash, authorDate, firstLine, jiraSlug));

                List<String> changedPaths = getChangedPaths(repo, commit, df);
                for (String path : changedPaths) {
                    changeBatch.add(new FileChangeRecord(hash, path));
                    totalChanges.computeIfAbsent(path, k -> new int[1])[0]++;
                    allChangedPaths.add(path);
                }

                accumulateCoChanges(changedPaths, coChanges);

                processed++;
                if (processed % BATCH_SIZE == 0) {
                    flush(commitBatch, changeBatch);
                }
            }

            if (!commitBatch.isEmpty()) {
                flush(commitBatch, changeBatch);
            }

            df.close();
            flushCoupling(coChanges, totalChanges);

            return new WalkResult(processed, allChangedPaths);
        }
    }

    private static ObjectId resolveDefaultBranch(Repository repo) throws IOException {
        ObjectId id = repo.resolve("refs/heads/main");
        if (id == null) id = repo.resolve("refs/heads/master");
        if (id == null) id = repo.resolve(Constants.HEAD);
        return id;
    }

    private static List<String> getChangedPaths(Repository repo, RevCommit commit,
                                                  DiffFormatter df) throws IOException {
        List<DiffEntry> diffs;
        if (commit.getParentCount() == 0) {
            try (ObjectReader reader = repo.newObjectReader()) {
                CanonicalTreeParser newTree = new CanonicalTreeParser();
                newTree.reset(reader, commit.getTree().getId());
                diffs = df.scan(new EmptyTreeIterator(), newTree);
            }
        } else {
            diffs = df.scan(commit.getParent(0).getTree(), commit.getTree());
        }

        List<String> paths = new ArrayList<>(diffs.size());
        for (DiffEntry entry : diffs) {
            switch (entry.getChangeType()) {
                case DELETE -> paths.add(entry.getOldPath());
                default     -> paths.add(entry.getNewPath());
            }
        }
        return paths;
    }

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

    private void flush(List<CommitRecord> commitBatch, List<FileChangeRecord> changeBatch) {
        if (!commitBatch.isEmpty()) commitDao.insertBatch(commitBatch);
        if (!changeBatch.isEmpty()) fileChangeDao.insertBatch(changeBatch);
        commitBatch.clear();
        changeBatch.clear();
    }

    private void flushCoupling(Map<String, int[]> coChanges, Map<String, int[]> totalChanges) {
        List<FileCouplingRecord> records = new ArrayList<>(coChanges.size());
        for (var entry : coChanges.entrySet()) {
            String[] parts = entry.getKey().split("\0", 2);
            String a = parts[0], b = parts[1];
            int co = entry.getValue()[0];
            int ta = totalChanges.getOrDefault(a, new int[]{0})[0];
            int tb = totalChanges.getOrDefault(b, new int[]{0})[0];
            records.add(new FileCouplingRecord(a, b, co, ta, tb));
        }
        if (!records.isEmpty()) {
            int chunkSize = 500;
            for (int i = 0; i < records.size(); i += chunkSize) {
                fileCouplingDao.upsertBatch(records.subList(i, Math.min(i + chunkSize, records.size())));
            }
        }
    }
}
