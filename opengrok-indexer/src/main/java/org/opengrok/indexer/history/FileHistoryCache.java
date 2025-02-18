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
 * Copyright (c) 2008, 2021, Oracle and/or its affiliates. All rights reserved.
 * Portions Copyright (c) 2018, 2020, Chris Fraire <cfraire@me.com>.
 */
package org.opengrok.indexer.history;

import java.beans.Encoder;
import java.beans.Expression;
import java.beans.PersistenceDelegate;
import java.beans.XMLDecoder;
import java.beans.XMLEncoder;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.opengrok.indexer.Metrics;
import org.opengrok.indexer.configuration.PathAccepter;
import org.opengrok.indexer.configuration.RuntimeEnvironment;
import org.opengrok.indexer.logger.LoggerFactory;
import org.opengrok.indexer.util.ForbiddenSymlinkException;
import org.opengrok.indexer.util.IOUtils;
import org.opengrok.indexer.util.TandemPath;

/**
 * Class representing file based storage of per source file history.
 */
class FileHistoryCache implements HistoryCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileHistoryCache.class);
    private static final RuntimeEnvironment env = RuntimeEnvironment.getInstance();

    private final Object lock = new Object();

    private static final String HISTORY_CACHE_DIR_NAME = "historycache";
    private static final String LATEST_REV_FILE_NAME = "OpenGroklatestRev";

    private final PathAccepter pathAccepter = env.getPathAccepter();
    private boolean historyIndexDone = false;

    private Counter fileHistoryCacheHits;
    private Counter fileHistoryCacheMisses;

    @Override
    public void setHistoryIndexDone() {
        historyIndexDone = true;
    }

    @Override
    public boolean isHistoryIndexDone() {
        return historyIndexDone;
    }

    /**
     * Generate history cache for single renamed file.
     * @param filename file path
     * @param repository repository
     * @param root root
     * @param tillRevision end revision
     */
    public void doRenamedFileHistory(String filename, File file, Repository repository, File root, String tillRevision)
            throws HistoryException {

        History history;

        if (tillRevision != null) {
            if (!(repository instanceof RepositoryWithPerPartesHistory)) {
                throw new RuntimeException("cannot use non null tillRevision on repository");
            }

            RepositoryWithPerPartesHistory repo = (RepositoryWithPerPartesHistory) repository;
            history = repo.getHistory(file, null, tillRevision);
        } else {
            history = repository.getHistory(file);
        }

        doFileHistory(filename, history, repository, root, true);
    }

    /**
     * Generate history cache for single file.
     * @param filename name of the file
     * @param history history
     * @param repository repository object in which the file belongs
     * @param root root of the source repository
     * @param renamed true if the file was renamed in the past
     */
    private void doFileHistory(String filename, History history, Repository repository, File root, boolean renamed)
            throws HistoryException {

        File file = new File(root, filename);
        if (file.isDirectory()) {
            return;
        }

        // File based history cache does not store files for individual
        // changesets so strip them unless it is history for the repository.
        history.strip();

        // Assign tags to changesets they represent.
        if (env.isTagsEnabled() && repository.hasFileBasedTags()) {
            repository.assignTagsInHistory(history);
        }

        storeFile(history, file, repository, !renamed);
    }

    private boolean isRenamedFile(String filename, Repository repository, History history)
            throws IOException {

        String repodir;
        try {
            repodir = env.getPathRelativeToSourceRoot(new File(repository.getDirectoryName()));
        } catch (ForbiddenSymlinkException e) {
            LOGGER.log(Level.FINER, e.getMessage());
            return false;
        }
        String shortestfile = filename.substring(repodir.length() + 1);

        return (history.isRenamed(shortestfile));
    }

    static class FilePersistenceDelegate extends PersistenceDelegate {
        @Override
        protected Expression instantiate(Object oldInstance, Encoder out) {
            File f = (File) oldInstance;
            return new Expression(oldInstance, f.getClass(), "new",
                new Object[] {f.toString()});
        }
    }

    @Override
    public void initialize() {
        MeterRegistry meterRegistry = Metrics.getRegistry();
        if (meterRegistry != null) {
            fileHistoryCacheHits = Counter.builder("filehistorycache.history.get").
                    description("file history cache hits").
                    tag("what", "hits").
                    register(meterRegistry);
            fileHistoryCacheMisses = Counter.builder("filehistorycache.history.get").
                    description("file history cache misses").
                    tag("what", "miss").
                    register(meterRegistry);
        }
    }

    @Override
    public void optimize() {
        // nothing to do
    }

    @Override
    public boolean supportsRepository(Repository repository) {
        // all repositories are supported
        return true;
    }

    /**
     * Get a <code>File</code> object describing the cache file.
     *
     * @param file the file to find the cache for
     * @return file that might contain cached history for <code>file</code>
     */
    private static File getCachedFile(File file) throws HistoryException,
            ForbiddenSymlinkException {

        StringBuilder sb = new StringBuilder();
        sb.append(env.getDataRootPath());
        sb.append(File.separatorChar);
        sb.append(HISTORY_CACHE_DIR_NAME);

        try {
            String add = env.getPathRelativeToSourceRoot(file);
            if (add.length() == 0) {
                add = File.separator;
            }
            sb.append(add);
        } catch (IOException e) {
            throw new HistoryException("Failed to get path relative to " +
                    "source root for " + file, e);
        }

        return new File(TandemPath.join(sb.toString(), ".gz"));
    }

    private static XMLDecoder getDecoder(InputStream in) {
        return new XMLDecoder(in, null, null, new HistoryClassLoader());
    }

    // for testing
    static History readCache(String xmlconfig) {
        final ByteArrayInputStream in = new ByteArrayInputStream(xmlconfig.getBytes());
        try (XMLDecoder d = getDecoder(in)) {
            return (History) d.readObject();
        }
    }

    /**
     * Read history from a file.
     */
    static History readCache(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file);
            XMLDecoder d = getDecoder(new GZIPInputStream(new BufferedInputStream(in)))) {
            return (History) d.readObject();
        }
    }

    /**
     * Store history in file on disk.
     * @param dir directory where the file will be saved
     * @param history history to store
     * @param cacheFile the file to store the history to
     */
    private void writeHistoryToFile(File dir, History history, File cacheFile) throws HistoryException {
        // We have a problem that multiple threads may access the cache layer
        // at the same time. Since I would like to avoid read-locking, I just
        // serialize the write access to the cache file. The generation of the
        // cache file would most likely be executed during index generation, and
        // that happens sequencial anyway....
        // Generate the file with a temporary name and move it into place when
        // I'm done so I don't have to protect the readers for partially updated
        // files...
        final File output;
        try {
            output = File.createTempFile("oghist", null, dir);
            try (FileOutputStream out = new FileOutputStream(output);
                XMLEncoder e = new XMLEncoder(new GZIPOutputStream(
                    new BufferedOutputStream(out)))) {
                e.setPersistenceDelegate(File.class,
                        new FilePersistenceDelegate());
                e.writeObject(history);
            }
        } catch (IOException ioe) {
            throw new HistoryException("Failed to write history", ioe);
        }
        synchronized (lock) {
            if (!cacheFile.delete() && cacheFile.exists()) {
                if (!output.delete()) {
                    LOGGER.log(Level.WARNING,
                            "Failed to remove temporary history cache file");
                }
                throw new HistoryException(
                        "Cachefile exists, and I could not delete it.");
            }
            if (!output.renameTo(cacheFile)) {
                if (!output.delete()) {
                    LOGGER.log(Level.WARNING,
                            "Failed to remove temporary history cache file");
                }
                throw new HistoryException("Failed to rename cache tmpfile.");
            }
        }
    }

    /**
     * Read history from cacheFile and merge it with histNew, return merged history.
     *
     * @param cacheFile file to where the history object will be stored
     * @param histNew history object with new history entries
     * @param repo repository to where pre pre-image of the cacheFile belong
     * @return merged history (can be null if merge failed for some reason)
     */
    private History mergeOldAndNewHistory(File cacheFile, History histNew, Repository repo) {

        History histOld;
        History history = null;

        try {
            histOld = readCache(cacheFile);
            // Merge old history with the new history.
            List<HistoryEntry> listOld = histOld.getHistoryEntries();
            if (!listOld.isEmpty()) {
                List<HistoryEntry> listNew = histNew.getHistoryEntries();
                ListIterator<HistoryEntry> li = listNew.listIterator(listNew.size());
                while (li.hasPrevious()) {
                    listOld.add(0, li.previous());
                }
                history = new History(listOld);

                // Retag the last changesets in case there have been some new
                // tags added to the repository. Technically we should just
                // retag the last revision from the listOld however this
                // does not solve the problem when listNew contains new tags
                // retroactively tagging changesets from listOld so we resort
                // to this somewhat crude solution.
                if (env.isTagsEnabled() && repo.hasFileBasedTags()) {
                    for (HistoryEntry ent : history.getHistoryEntries()) {
                        ent.setTags(null);
                    }
                    repo.assignTagsInHistory(history);
                }
            }
        } catch (IOException ex) {
            LOGGER.log(Level.SEVERE,
                String.format("Cannot open history cache file %s", cacheFile.getPath()), ex);
        }

        return history;
    }

    /**
     * Store history object (encoded as XML and compressed with gzip) in a file.
     *
     * @param histNew history object to store
     * @param file file to store the history object into
     * @param repo repository for the file
     * @param mergeHistory whether to merge the history with existing or
     *                     store the histNew as is
     */
    private void storeFile(History histNew, File file, Repository repo,
            boolean mergeHistory) throws HistoryException {

        File cacheFile;
        try {
            cacheFile = getCachedFile(file);
        } catch (ForbiddenSymlinkException e) {
            LOGGER.log(Level.FINER, e.getMessage());
            return;
        }
        History history = histNew;

        File dir = cacheFile.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new HistoryException(
                    "Unable to create cache directory '" + dir + "'.");
        }

        if (mergeHistory && cacheFile.exists()) {
            history = mergeOldAndNewHistory(cacheFile, histNew, repo);
        }

        // If the merge failed, null history will be returned.
        // In such case store at least new history as a best effort.
        if (history == null) {
            history = histNew;
        }

        writeHistoryToFile(dir, history, cacheFile);
    }

    private void storeFile(History histNew, File file, Repository repo)
            throws HistoryException {
        storeFile(histNew, file, repo, false);
    }

    private void finishStore(Repository repository, String latestRev) {
        String histDir = getRepositoryHistDataDirname(repository);
        if (histDir == null || !(new File(histDir)).isDirectory()) {
            // If the history was not created for some reason (e.g. temporary
            // failure), do not create the CachedRevision file as this would
            // create confusion (once it starts working again).
            LOGGER.log(Level.WARNING,
                "Could not store history for repository {0}: {1} is not a directory",
                new Object[]{repository.getDirectoryName(), histDir});
        } else {
            storeLatestCachedRevision(repository, latestRev);
            LOGGER.log(Level.FINE,
                "Done storing history for repository {0}",
                repository.getDirectoryName());
        }
    }

    @Override
    public void store(History history, Repository repository) throws HistoryException {
        store(history, repository, null);
    }

    /**
     * Store history for the whole repository in directory hierarchy resembling
     * the original repository structure. History of individual files will be
     * stored under this hierarchy, each file containing history of
     * corresponding source file.
     *
     * @param history history object to process into per-file histories
     * @param repository repository object
     */
    @Override
    public void store(History history, Repository repository, String tillRevision) throws HistoryException {

        final boolean handleRenamedFiles = repository.isHandleRenamedFiles();

        String latestRev = null;

        // Return immediately when there is nothing to do.
        List<HistoryEntry> entries = history.getHistoryEntries();
        if (entries.isEmpty()) {
            return;
        }

        HashMap<String, List<HistoryEntry>> map = new HashMap<>();
        HashMap<String, Boolean> acceptanceCache = new HashMap<>();
        Set<String> renamedFiles = new HashSet<>();

        /*
         * Go through all history entries for this repository (acquired through
         * history/log command executed for top-level directory of the repo
         * and parsed into HistoryEntry structures) and create hash map which
         * maps file names into list of HistoryEntry structures corresponding
         * to changesets in which the file was modified.
         */
        for (HistoryEntry e : history.getHistoryEntries()) {
            // The history entries are sorted from newest to oldest.
            if (latestRev == null) {
                latestRev = e.getRevision();
            }
            for (String s : e.getFiles()) {
                /*
                 * We do not want to generate history cache for files which
                 * do not currently exist in the repository.
                 *
                 * Also we cache the result of this evaluation to boost
                 * performance, since a particular file can appear in many
                 * repository revisions.
                 */
                File test = new File(env.getSourceRootPath() + s);
                String testKey = test.getAbsolutePath();
                Boolean cachedAcceptance = acceptanceCache.get(testKey);
                if (cachedAcceptance != null) {
                    if (!cachedAcceptance) {
                        continue;
                    }
                } else {
                    boolean testResult = test.exists() && pathAccepter.accept(test);
                    acceptanceCache.put(testKey, testResult);
                    if (!testResult) {
                        continue;
                    }
                }

                List<HistoryEntry> list = map.computeIfAbsent(s, k -> new ArrayList<>());

                list.add(e);
            }
        }

        File histDataDir = new File(getRepositoryHistDataDirname(repository));
        if (!histDataDir.isDirectory() && !histDataDir.mkdirs()) {
            LOGGER.log(Level.WARNING, "cannot create history cache directory for ''{0}''", histDataDir);
        }

        /*
         * Now traverse the list of files from the hash map built above
         * and for each file store its history (saved in the value of the
         * hash map entry for the file) in a file. Skip renamed files
         * which will be handled separately below.
         */
        LOGGER.log(Level.FINE, "Storing history for {0} files in repository ''{1}''",
                new Object[]{map.entrySet().size(), repository.getDirectoryName()});
        final File root = env.getSourceRootFile();
        int fileHistoryCount = 0;
        for (Map.Entry<String, List<HistoryEntry>> map_entry : map.entrySet()) {
            try {
                if (handleRenamedFiles && isRenamedFile(map_entry.getKey(), repository, history)) {
                    renamedFiles.add(map_entry.getKey());
                    continue;
                }
            } catch (IOException ex) {
               LOGGER.log(Level.WARNING, "isRenamedFile() got exception", ex);
            }

            doFileHistory(map_entry.getKey(), new History(map_entry.getValue()),
                    repository, root, false);
            fileHistoryCount++;
        }

        LOGGER.log(Level.FINE, "Stored history for {0} files in repository ''{1}''",
                new Object[]{fileHistoryCount, repository.getDirectoryName()});

        if (!handleRenamedFiles) {
            finishStore(repository, latestRev);
            return;
        }

        storeRenamed(renamedFiles, repository, tillRevision);

        finishStore(repository, latestRev);
    }

    /**
     * handle renamed files (in parallel).
     * @param renamedFiles set of renamed file paths
     * @param repository repository
     */
    public void storeRenamed(Set<String> renamedFiles, Repository repository, String tillRevision) throws HistoryException {
        final File root = env.getSourceRootFile();
        if (renamedFiles.isEmpty()) {
            return;
        }

        renamedFiles = renamedFiles.stream().filter(f -> new File(env.getSourceRootPath() + f).exists()).
                collect(Collectors.toSet());
        LOGGER.log(Level.FINE, "Storing history for {0} renamed files in repository ''{1}''",
                new Object[]{renamedFiles.size(), repository.getDirectoryName()});

        // The directories for the renamed files have to be created before
        // the actual files otherwise storeFile() might be racing for
        // mkdirs() if there are multiple renamed files from single directory
        // handled in parallel.
        for (final String file : renamedFiles) {
            File cache;
            try {
                cache = getCachedFile(new File(env.getSourceRootPath() + file));
            } catch (ForbiddenSymlinkException ex) {
                LOGGER.log(Level.FINER, ex.getMessage());
                continue;
            }
            File dir = cache.getParentFile();

            if (!dir.isDirectory() && !dir.mkdirs()) {
                LOGGER.log(Level.WARNING,
                        "Unable to create cache directory ' {0} '.", dir);
            }
        }
        final Repository repositoryF = repository;
        final CountDownLatch latch = new CountDownLatch(renamedFiles.size());
        AtomicInteger renamedFileHistoryCount = new AtomicInteger();
        for (final String file : renamedFiles) {
            env.getIndexerParallelizer().getHistoryRenamedExecutor().submit(() -> {
                try {
                    doRenamedFileHistory(file,
                            new File(env.getSourceRootPath() + file),
                            repositoryF, root, tillRevision);
                    renamedFileHistoryCount.getAndIncrement();
                } catch (Exception ex) {
                    // We want to catch any exception since we are in thread.
                    LOGGER.log(Level.WARNING,
                            "doFileHistory() got exception ", ex);
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for the executors to finish.
        try {
            // Wait for the executors to finish.
            latch.await();
        } catch (InterruptedException ex) {
            LOGGER.log(Level.SEVERE, "latch exception", ex);
        }
        LOGGER.log(Level.FINE, "Stored history for {0} renamed files in repository ''{1}''",
                new Object[]{renamedFileHistoryCount.intValue(), repository.getDirectoryName()});
    }

    @Override
    public History get(File file, Repository repository, boolean withFiles)
            throws HistoryException, ForbiddenSymlinkException {
        File cache = getCachedFile(file);
        if (isUpToDate(file, cache)) {
            try {
                if (fileHistoryCacheHits != null) {
                    fileHistoryCacheHits.increment();
                }
                return readCache(cache);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING,
                        "Error when reading cache file '" + cache, e);
            }
        }

        if (fileHistoryCacheMisses != null) {
            fileHistoryCacheMisses.increment();
        }
        /*
         * Some mirrors of repositories which are capable of fetching history
         * for directories may contain lots of files untracked by given SCM.
         * For these it would be waste of time to get their history
         * since the history of all files in this repository should have been
         * fetched in the first phase of indexing.
         */
        if (isHistoryIndexDone() && repository.isHistoryEnabled() &&
                repository.hasHistoryForDirectories() &&
                !env.isFetchHistoryWhenNotInCache()) {
            return null;
        }

        if (!pathAccepter.accept(file)) {
            return null;
        }

        final History history;
        long time;
        try {
            time = System.currentTimeMillis();
            history = repository.getHistory(file);
            time = System.currentTimeMillis() - time;
        } catch (UnsupportedOperationException e) {
            // In this case, we've found a file for which the SCM has no history
            // An example is a non-SCCS file somewhere in an SCCS-controlled
            // workspace.
            return null;
        }

        if (!file.isDirectory()) {
            // Don't cache history-information for directories, since the
            // history information on the directory may change if a file in
            // a sub-directory change. This will cause us to present a stale
            // history log until a the current directory is updated and
            // invalidates the cache entry.
            if ((cache != null) &&
                        (cache.exists() ||
                             (time > env.getHistoryReaderTimeLimit()))) {
                // retrieving the history takes too long, cache it!
                storeFile(history, file, repository);
            }
        }

        return history;
    }

    /**
     * Check if the cache is up to date for the specified file.
     * @param file the file to check
     * @param cachedFile the file which contains the cached history for
     * the file
     * @return {@code true} if the cache is up to date, {@code false} otherwise
     */
    private boolean isUpToDate(File file, File cachedFile) {
        return cachedFile != null && cachedFile.exists() &&
                file.lastModified() <= cachedFile.lastModified();
    }

    @Override
    public boolean hasCacheForFile(File file) throws HistoryException {
        try {
            return getCachedFile(file).exists();
        } catch (ForbiddenSymlinkException ex) {
            LOGGER.log(Level.FINER, ex.getMessage());
            return false;
        }
    }

    public String getRepositoryHistDataDirname(Repository repository) {
        String repoDirBasename;

        try {
            repoDirBasename = env.getPathRelativeToSourceRoot(
                    new File(repository.getDirectoryName()));
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Could not resolve " +
                repository.getDirectoryName() + " relative to source root", ex);
            return null;
        } catch (ForbiddenSymlinkException ex) {
            LOGGER.log(Level.FINER, ex.getMessage());
            return null;
        }

        return env.getDataRootPath() + File.separatorChar
            + FileHistoryCache.HISTORY_CACHE_DIR_NAME
            + repoDirBasename;
    }

    private String getRepositoryCachedRevPath(Repository repository) {
        String histDir = getRepositoryHistDataDirname(repository);
        if (histDir == null) {
            return null;
        }
        return histDir + File.separatorChar + LATEST_REV_FILE_NAME;
    }

    /**
     * Store latest indexed revision for the repository under data directory.
     * @param repository repository
     * @param rev latest revision which has been just indexed
     */
    private void storeLatestCachedRevision(Repository repository, String rev) {
        Writer writer = null;

        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                  new FileOutputStream(getRepositoryCachedRevPath(repository))));
            writer.write(rev);
        } catch (IOException ex) {
            LOGGER.log(Level.WARNING, "Cannot write latest cached revision to file for " + repository.getDirectoryName(),
                ex);
        } finally {
           try {
               if (writer != null) {
                   writer.close();
               }
           } catch (IOException ex) {
               LOGGER.log(Level.WARNING, "Cannot close file", ex);
           }
        }
    }

    @Override
    public String getLatestCachedRevision(Repository repository) {
        String rev;
        BufferedReader input;

        String revPath = getRepositoryCachedRevPath(repository);
        if (revPath == null) {
            LOGGER.log(Level.WARNING, "no rev path for {0}",
                repository.getDirectoryName());
            return null;
        }

        try {
            input = new BufferedReader(new FileReader(revPath));
            try {
                rev = input.readLine();
            } catch (java.io.IOException e) {
                LOGGER.log(Level.WARNING, "failed to load ", e);
                return null;
            } finally {
                try {
                    input.close();
                } catch (java.io.IOException e) {
                    LOGGER.log(Level.WARNING, "failed to close", e);
                }
            }
        } catch (java.io.FileNotFoundException e) {
            LOGGER.log(Level.FINE,
                "not loading latest cached revision file from {0}", revPath);
            return null;
        }

        return rev;
    }

    @Override
    public Map<String, Date> getLastModifiedTimes(
            File directory, Repository repository) {
        // We don't have a good way to get this information from the file
        // cache, so leave it to the caller to find a reasonable time to
        // display (typically the last modified time on the file system).
        return Collections.emptyMap();
    }

    @Override
    public void clear(Repository repository) {
        String revPath = getRepositoryCachedRevPath(repository);
        if (revPath != null) {
            // remove the file cached last revision (done separately in case
            // it gets ever moved outside of the hierarchy)
            File cachedRevFile = new File(revPath);
            cachedRevFile.delete();
        }

        String histDir = getRepositoryHistDataDirname(repository);
        if (histDir != null) {
            // Remove all files which constitute the history cache.
            try {
                IOUtils.removeRecursive(Paths.get(histDir));
            } catch (NoSuchFileException ex) {
                LOGGER.log(Level.WARNING, String.format("directory %s does not exist", histDir));
            } catch (IOException ex) {
                LOGGER.log(Level.SEVERE, "tried removeRecursive()", ex);
            }
        }
    }

    @Override
    public void clearFile(String path) {
        File historyFile;
        try {
            historyFile = getCachedFile(new File(env.getSourceRootPath() + path));
        } catch (ForbiddenSymlinkException ex) {
            LOGGER.log(Level.FINER, ex.getMessage());
            return;
        } catch (HistoryException ex) {
            LOGGER.log(Level.WARNING, "cannot get history file for file " + path, ex);
            return;
        }
        File parent = historyFile.getParentFile();

        if (!historyFile.delete() && historyFile.exists()) {
            LOGGER.log(Level.WARNING,
                "Failed to remove obsolete history cache-file: {0}",
                historyFile.getAbsolutePath());
        }

        if (parent.delete()) {
            LOGGER.log(Level.FINE, "Removed empty history cache dir:{0}",
                parent.getAbsolutePath());
        }
    }

    @Override
    public String getInfo() {
        return getClass().getSimpleName();
    }
}
