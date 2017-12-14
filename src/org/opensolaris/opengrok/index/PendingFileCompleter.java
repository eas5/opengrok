/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

/*
 * Copyright (c) 2017, Chris Fraire <cfraire@me.com>.
 */

package org.opensolaris.opengrok.index;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.opensolaris.opengrok.logger.LoggerFactory;

/**
 * Represents a tracker of pending file deletions and renamings that can later
 * be executed.
 */
public class PendingFileCompleter {

    /**
     * An extension that should be used as the suffix of files for
     * {@link PendingFileRenaming} actions.
     * <p>Value is {@code ".org_opengrok"}.
     */
    public static final String PENDING_EXTENSION = ".org_opengrok";

    private static final Logger LOGGER =
        LoggerFactory.getLogger(PendingFileCompleter.class);

    /**
     * Descending path segment length comparator
     */
    private static final Comparator<File> DESC_PATHLEN_COMPARATOR =
        (File f1, File f2) -> {
            String s1 = f1.getAbsolutePath();
            String s2 = f2.getAbsolutePath();
            int n1 = countPathSegments(s1);
            int n2 = countPathSegments(s2);
            // DESC: s2 no. of segments <=> s1 no. of segments
            int cmp = Integer.compare(n2, n1);
            if (cmp != 0) return cmp;

            // the Comparator must also be "consistent with equals", so check
            // string contents too when (length)cmp == 0. (ASC: s1 <=> s2.)
            cmp = s1.compareTo(s2);
            return cmp;
    };

    private final Set<PendingFileDeletion> deletions = new HashSet<>();

    private final Set<PendingFileRenaming> renames = new HashSet<>();

    /**
     * Adds the specified element to this instance's set if it is not already
     * present.
     * @param e element to be added to this set
     * @return {@code true} if this instance's set did not already contain the
     * specified element
     */
    public boolean add(PendingFileDeletion e) {
        return deletions.add(e);
    }

    /**
     * Removes the specified element from this instance's set if it is present.
     * @param e element to be removed to this set
     * @return {@code true} if this instance's set did not already contain the
     * specified element
     */
    public boolean remove(PendingFileDeletion e) {
        return deletions.remove(e);
    }

    /**
     * Adds the specified element to this instance's set if it is not already
     * present, and also remove any pending deletion for the same absolute
     * path.
     * @param e element to be added to this set
     * @return {@code true} if this instance's set did not already contain the
     * specified element
     */
    public boolean add(PendingFileRenaming e) {
        boolean rc = renames.add(e);
        deletions.remove(new PendingFileDeletion(e.getAbsolutePath()));
        return rc;
    }

    /**
     * Complete all the tracked file operations: first in a stage for pending
     * deletions and then in a stage for pending renamings.
     * <p>
     * All operations in each stage are tried in parallel, and any failure is
     * caught and raises an exception (after all items in the stage have been
     * tried).
     * <p>
     * Deletions are tried for each
     * {@link PendingFileDeletion#getAbsolutePath()}; for a version of the path
     * with {@link #PENDING_EXTENSION} appended; and also for the path's parent
     * directory, which does nothing if the directory is not empty.
     * @return the number of successful deletions and renamings
     * @throws java.io.IOException
     */
    public int complete() throws IOException {
        int numDeletions = completeDeletions();
        LOGGER.log(Level.FINE, "deleted {0} file(s)", numDeletions);
        int numRenamings = completeRenamings();
        LOGGER.log(Level.FINE, "renamed {0} file(s)", numRenamings);
        return numDeletions + numRenamings;
    }

    /**
     * Attempts to rename all the tracked elements, catching any failures, and
     * throwing an exception if any failed.
     * @return the number of successful renamings
     * @throws java.io.IOException
     */
    private int completeRenamings() throws IOException {
        int numPending = renames.size();
        int numFailures = 0;

        if (numPending < 1) return 0;

        List<PendingFileRenamingExec> pendingExecs = renames.
            parallelStream().map(f ->
            new PendingFileRenamingExec(f.getTransientPath(),
                f.getAbsolutePath())).collect(
            Collectors.toList());

        Map<Boolean, List<PendingFileRenamingExec>> bySuccess =
            pendingExecs.parallelStream().collect(
            Collectors.groupingByConcurrent((x) -> {
                try {
                    doRename(x);
                    return true;
                } catch (IOException e) {
                    x.exception = e;
                    return false;
                }
            }));
        renames.clear();

        List<PendingFileRenamingExec> failures = bySuccess.getOrDefault(
            Boolean.FALSE, null);
        if (failures != null && failures.size() > 0) {
            numFailures = failures.size();
            double pctFailed = 100.0 * numFailures / numPending;
            String exmsg = String.format(
                "%d failures (%.1f%%) while renaming pending files",
                numFailures, pctFailed);
            throw new IOException(exmsg, failures.get(0).exception);
        }

        return numPending - numFailures;
    }

    /**
     * Attempts to delete all the tracked elements, catching any failures, and
     * throwing an exception if any failed.
     * @return the number of successful deletions
     * @throws java.io.IOException
     */
    private int completeDeletions() throws IOException {
        int numPending = deletions.size();
        int numFailures = 0;

        if (numPending < 1) return 0;

        List<PendingFileDeletionExec> pendingExecs = deletions.
            parallelStream().map(f ->
            new PendingFileDeletionExec(f.getAbsolutePath())).collect(
            Collectors.toList());

        Map<Boolean, List<PendingFileDeletionExec>> bySuccess =
            pendingExecs.parallelStream().collect(
            Collectors.groupingByConcurrent((x) -> {
                try {
                    doDelete(x);
                    return true;
                } catch (IOException e) {
                    x.exception = e;
                    return false;
                }
            }));
        deletions.clear();

        List<PendingFileDeletionExec> successes = bySuccess.getOrDefault(
            Boolean.TRUE, null);
        if (successes != null) tryDeleteParents(successes);

        List<PendingFileDeletionExec> failures = bySuccess.getOrDefault(
            Boolean.FALSE, null);
        if (failures != null && failures.size() > 0) {
            numFailures = failures.size();
            double pctFailed = 100.0 * numFailures / numPending;
            String exmsg = String.format(
                "%d failures (%.1f%%) while deleting pending files",
                numFailures, pctFailed);
            throw new IOException(exmsg, failures.get(0).exception);
        }

        return numPending - numFailures;
    }

    private void doDelete(PendingFileDeletionExec del) throws IOException {
        File f = new File(del.absolutePath + PENDING_EXTENSION);
        File parent = f.getParentFile();
        del.absoluteParent = parent;

        doDelete(f);
        f = new File(del.absolutePath);
        doDelete(f);
    }

    private void doDelete(File f) {
        if (f.delete()) {
            LOGGER.log(Level.FINER, "Deleted obsolete file: {0}", f.getPath());
        } else if (f.exists()) {
            LOGGER.log(Level.WARNING, "Failed to delete obsolete file: {0}",
                    f.getPath());
        }
    }

    private void doRename(PendingFileRenamingExec ren) throws IOException {
        try {
            Files.move(Paths.get(ren.source), Paths.get(ren.target),
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to move file: {0}\n\t=> {1}",
                new Object[]{ren.source, ren.target});
                throw e;
        }
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "Moved pending as file: {0}",
                ren.target);
        }
    }

    private void tryDeleteParents(List<PendingFileDeletionExec> dels) {
        Set<File> parents = new TreeSet<>(DESC_PATHLEN_COMPARATOR);
        dels.forEach((del) -> { parents.add(del.absoluteParent); });

        for (File dir : parents) {
            Set<File> children = findFilelessChildren(dir);
            children.forEach((childDir) -> {
                tryDeleteDirectory(childDir);
            });

            tryDeleteDirectory(dir);
        }
    }

    private void tryDeleteDirectory(File dir) {
        if (dir.delete()) {
            LOGGER.log(Level.FINE, "Removed empty parent dir: {0}",
                dir.getAbsolutePath());
        }
    }

    /**
     * Determines a DESC ordered set of eligible file-less child directories
     * for cleaning up.
     */
    private Set<File> findFilelessChildren(File directory) {
        SkeletonDirs ret = new SkeletonDirs();
        findFilelessChildrenDeep(ret, directory);

        Set<File> parents = new TreeSet<>(DESC_PATHLEN_COMPARATOR);
        // N.b. the `ineligible' field is not relevant here but only during
        // recursion. `childDirs' contains eligible directories.
        parents.addAll(ret.childDirs);
        return parents;
    }

    /**
     * Recursive method used by {@link #findFilelessChildren(java.io.File)}.
     */
    private void findFilelessChildrenDeep(SkeletonDirs ret, File directory) {

        if (!directory.exists()) return;
        String dirPath = directory.getAbsolutePath();
        boolean topLevelIneligible = false;
        boolean didLogFileTopLevelIneligible = false;

        try {
            DirectoryStream<Path> directoryStream = Files.newDirectoryStream(
                Paths.get(dirPath));
            for (Path path : directoryStream) {
                File f = path.toFile();
                if (f.isFile()) {
                    topLevelIneligible = true;
                    if (!didLogFileTopLevelIneligible && LOGGER.isLoggable(
                        Level.FINEST)) {
                        didLogFileTopLevelIneligible = true; // just once is OK
                        LOGGER.log(Level.FINEST, "not file-less due to: {0}",
                            f.getAbsolutePath());
                    }
                } else {
                    findFilelessChildrenDeep(ret, f);
                    if (!ret.ineligible) {
                        ret.childDirs.add(f);
                    } else {
                        topLevelIneligible = true;
                        if (LOGGER.isLoggable(Level.FINEST)) {
                            LOGGER.log(Level.FINEST,
                                "its children prevent delete: {0}",
                                f.getAbsolutePath());
                        }
                    }

                    // Reset this flag so that other potential, eligible
                    // children are evaluated.
                    ret.ineligible = false;
                }
            }
        } catch (IOException ex) {
            topLevelIneligible = true;
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.log(Level.FINEST, "Failed to stream directory:" +
                    directory, ex);
            }
        }

        ret.ineligible = topLevelIneligible;
    }

    /**
     * Counts segments arising from {@code File.separatorChar} or '\\'
     * @param path
     * @return a natural number
     */
    private static int countPathSegments(String path) {
        int n = 1;
        for (int i = 0; i < path.length(); ++i) {
            char c = path.charAt(i);
            if (c == File.separatorChar || c == '\\') ++n;
        }
        return n;
    }

    private class PendingFileDeletionExec {
        public String absolutePath;
        public File absoluteParent;
        public IOException exception;
        public PendingFileDeletionExec(String absolutePath) {
            this.absolutePath = absolutePath;
        }
    }

    private class PendingFileRenamingExec {
        public String source;
        public String target;
        public IOException exception;
        public PendingFileRenamingExec(String source, String target) {
            this.source = source;
            this.target = target;
        }
    }

    /**
     * Represents a collection of file-less directories which should also be
     * deleted for cleanliness.
     */
    private class SkeletonDirs {
        public boolean ineligible; // a flag used during recursion
        public List<File> childDirs = new ArrayList<>();
    }
}
