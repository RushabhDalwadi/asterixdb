/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hyracks.storage.am.lsm.common.impls;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import org.apache.hyracks.api.exceptions.ErrorCode;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.api.io.FileReference;
import org.apache.hyracks.api.io.IIOManager;
import org.apache.hyracks.api.util.IoUtil;
import org.apache.hyracks.storage.am.common.api.ITreeIndex;
import org.apache.hyracks.storage.am.common.api.ITreeIndexFrame;
import org.apache.hyracks.storage.am.common.api.ITreeIndexMetadataFrame;
import org.apache.hyracks.storage.am.lsm.common.api.ILSMIndexFileManager;
import org.apache.hyracks.storage.common.buffercache.IBufferCache;
import org.apache.hyracks.storage.common.buffercache.ICachedPage;
import org.apache.hyracks.storage.common.file.BufferedFileHandle;

public abstract class AbstractLSMIndexFileManager implements ILSMIndexFileManager {

    public enum TreeIndexState {
        INVALID,
        VERSION_MISMATCH,
        VALID
    }

    /**
     * Split different parts of the file name
     */
    public static final String DELIMITER = "_";
    /**
     * Indicates a B tree
     */
    public static final String BTREE_SUFFIX = "b";
    /**
     * Indicates an R tree
     */
    public static final String RTREE_SUFFIX = "r";
    /**
     * Indicates a bloom filter
     */
    public static final String BLOOM_FILTER_SUFFIX = "f";
    /**
     * Indicates a delete tree
     */
    public static final String DELETE_TREE_SUFFIX = "d";
    /**
     * Hides transaction components until they are either committed by removing this file or deleted along with the file
     */
    public static final String TXN_PREFIX = ".T";

    protected static final FilenameFilter fileNameFilter = (dir, name) -> !name.startsWith(".");
    protected static final FilenameFilter txnFileNameFilter = (dir, name) -> name.startsWith(TXN_PREFIX);
    protected static FilenameFilter bloomFilterFilter =
            (dir, name) -> !name.startsWith(".") && name.endsWith(BLOOM_FILTER_SUFFIX);
    protected static final FilenameFilter dummyFilter = (dir, name) -> true;
    protected static final Comparator<String> cmp = new FileNameComparator();

    protected final IIOManager ioManager;
    // baseDir should reflect dataset name and partition name and be absolute
    protected final String baseDir;
    protected final Format formatter = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS");
    protected final Comparator<ComparableFileName> recencyCmp = new RecencyComparator();
    protected final TreeIndexFactory<? extends ITreeIndex> treeFactory;
    private String prevTimestamp = null;

    public AbstractLSMIndexFileManager(IIOManager ioManager, FileReference file,
            TreeIndexFactory<? extends ITreeIndex> treeFactory) {
        this.ioManager = ioManager;
        this.baseDir = file.getFile().getAbsolutePath().endsWith(File.separator) ? file.getFile().getAbsolutePath()
                : file.getFile().getAbsolutePath() + File.separator;
        this.treeFactory = treeFactory;
    }

    protected TreeIndexState isValidTreeIndex(ITreeIndex treeIndex) throws HyracksDataException {
        IBufferCache bufferCache = treeIndex.getBufferCache();
        treeIndex.activate();
        try {
            int metadataPage = treeIndex.getPageManager().getMetadataPageId();
            if (metadataPage < 0) {
                return TreeIndexState.INVALID;
            }
            ITreeIndexMetadataFrame metadataFrame = treeIndex.getPageManager().createMetadataFrame();
            ICachedPage page =
                    bufferCache.pin(BufferedFileHandle.getDiskPageId(treeIndex.getFileId(), metadataPage), false);
            page.acquireReadLatch();
            try {
                metadataFrame.setPage(page);
                if (!metadataFrame.isValid()) {
                    return TreeIndexState.INVALID;
                } else if (metadataFrame.getVersion() != ITreeIndexFrame.Constants.VERSION) {
                    return TreeIndexState.VERSION_MISMATCH;
                } else {
                    return TreeIndexState.VALID;
                }
            } finally {
                page.releaseReadLatch();
                bufferCache.unpin(page);
            }
        } finally {
            treeIndex.deactivate();
        }
    }

    protected void cleanupAndGetValidFilesInternal(FilenameFilter filter,
            TreeIndexFactory<? extends ITreeIndex> treeFactory, ArrayList<ComparableFileName> allFiles)
            throws HyracksDataException {
        String[] files = listDirFiles(baseDir, filter);
        File dir = new File(baseDir);
        for (String fileName : files) {
            FileReference fileRef = ioManager.resolveAbsolutePath(dir.getPath() + File.separator + fileName);
            if (treeFactory == null) {
                allFiles.add(new ComparableFileName(fileRef));
                continue;
            }
            TreeIndexState idxState = isValidTreeIndex(treeFactory.createIndexInstance(fileRef));
            if (idxState == TreeIndexState.VALID) {
                allFiles.add(new ComparableFileName(fileRef));
            } else if (idxState == TreeIndexState.INVALID) {
                fileRef.delete();
            }
        }
    }

    static String[] listDirFiles(String path, FilenameFilter filter) throws HyracksDataException {
        File dir = new File(path);
        /*
         * Returns null if this abstract pathname does not denote a directory, or if an I/O error occurs.
         */
        String[] files = dir.list(filter);
        if (files == null) {
            if (!dir.canRead()) {
                throw HyracksDataException.create(ErrorCode.CANNOT_READ_FILE, path);
            } else if (!dir.exists()) {
                throw HyracksDataException.create(ErrorCode.FILE_DOES_NOT_EXIST, path);
            } else if (!dir.isDirectory()) {
                throw HyracksDataException.create(ErrorCode.FILE_IS_NOT_DIRECTORY, path);
            }
            throw HyracksDataException.create(ErrorCode.UNIDENTIFIED_IO_ERROR_READING_FILE, path);
        }
        return files;
    }

    protected void validateFiles(HashSet<String> groundTruth, ArrayList<ComparableFileName> validFiles,
            FilenameFilter filter, TreeIndexFactory<? extends ITreeIndex> treeFactory) throws HyracksDataException {
        ArrayList<ComparableFileName> tmpAllInvListsFiles = new ArrayList<>();
        cleanupAndGetValidFilesInternal(filter, treeFactory, tmpAllInvListsFiles);
        for (ComparableFileName cmpFileName : tmpAllInvListsFiles) {
            int index = cmpFileName.fileName.lastIndexOf(DELIMITER);
            String file = cmpFileName.fileName.substring(0, index);
            if (groundTruth.contains(file)) {
                validFiles.add(cmpFileName);
            } else {
                File invalidFile = new File(cmpFileName.fullPath);
                IoUtil.delete(invalidFile);
            }
        }
    }

    @Override
    public void createDirs() throws HyracksDataException {
        File f = new File(baseDir);
        if (f.exists()) {
            throw HyracksDataException.create(ErrorCode.CANNOT_CREATE_EXISTING_INDEX);
        }
        f.mkdirs();
    }

    @Override
    public void deleteDirs() throws HyracksDataException {
        IoUtil.delete(new File(baseDir));
    }

    protected FileReference createFlushFile(String flushFileName) throws HyracksDataException {
        return ioManager.resolveAbsolutePath(flushFileName);
    }

    protected FileReference createMergeFile(String mergeFileName) throws HyracksDataException {
        return createFlushFile(mergeFileName);
    }

    @Override
    public LSMComponentFileReferences getRelFlushFileReference() throws HyracksDataException {
        String ts = getCurrentTimestamp();
        // Begin timestamp and end timestamp are identical since it is a flush
        return new LSMComponentFileReferences(createFlushFile(baseDir + ts + DELIMITER + ts), null, null);
    }

    @Override
    public LSMComponentFileReferences getRelMergeFileReference(String firstFileName, String lastFileName)
            throws HyracksDataException {
        String[] firstTimestampRange = firstFileName.split(DELIMITER);
        String[] lastTimestampRange = lastFileName.split(DELIMITER);
        // Get the range of timestamps by taking the earliest and the latest timestamps
        return new LSMComponentFileReferences(
                createMergeFile(baseDir + firstTimestampRange[0] + DELIMITER + lastTimestampRange[1]), null, null);
    }

    @Override
    public List<LSMComponentFileReferences> cleanupAndGetValidFiles() throws HyracksDataException {
        List<LSMComponentFileReferences> validFiles = new ArrayList<>();
        ArrayList<ComparableFileName> allFiles = new ArrayList<>();

        // Gather files and delete invalid files
        // There are two types of invalid files:
        // (1) The isValid flag is not set
        // (2) The file's interval is contained by some other file
        // Here, we only filter out (1).
        cleanupAndGetValidFilesInternal(fileNameFilter, treeFactory, allFiles);

        if (allFiles.isEmpty()) {
            return validFiles;
        }

        if (allFiles.size() == 1) {
            validFiles.add(new LSMComponentFileReferences(allFiles.get(0).fileRef, null, null));
            return validFiles;
        }

        // Sorts files names from earliest to latest timestamp.
        Collections.sort(allFiles);

        List<ComparableFileName> validComparableFiles = new ArrayList<>();
        ComparableFileName last = allFiles.get(0);
        validComparableFiles.add(last);
        for (int i = 1; i < allFiles.size(); i++) {
            ComparableFileName current = allFiles.get(i);
            // The current start timestamp is greater than last stop timestamp so current is valid.
            if (current.interval[0].compareTo(last.interval[1]) > 0) {
                validComparableFiles.add(current);
                last = current;
            } else if (current.interval[0].compareTo(last.interval[0]) >= 0
                    && current.interval[1].compareTo(last.interval[1]) <= 0) {
                // The current file is completely contained in the interval of the
                // last file. Thus the last file must contain at least as much information
                // as the current file, so delete the current file.
                current.fileRef.delete();
            } else {
                // This scenario should not be possible since timestamps are monotonically increasing.
                throw new HyracksDataException("Found LSM files with overlapping timestamp intervals, "
                        + "but the intervals were not contained by another file.");
            }
        }

        // Sort valid files in reverse lexicographical order, such that newer files come first.
        Collections.sort(validComparableFiles, recencyCmp);
        for (ComparableFileName cmpFileName : validComparableFiles) {
            validFiles.add(new LSMComponentFileReferences(cmpFileName.fileRef, null, null));
        }

        return validFiles;
    }

    @Override
    public Comparator<String> getFileNameComparator() {
        return cmp;
    }

    /**
     * Sorts strings in reverse lexicographical order. The way we construct the
     * file names above guarantees that:
     * 1. Flushed files sort lower than merged files
     * 2. Flushed files are sorted from newest to oldest (based on the timestamp
     * string)
     */
    private static class FileNameComparator implements Comparator<String> {
        @Override
        public int compare(String a, String b) {
            // Consciously ignoring locale.
            return -a.compareTo(b);
        }
    }

    @Override
    public String getBaseDir() {
        return baseDir;
    }

    @Override
    public void recoverTransaction() throws HyracksDataException {
        String[] files = listDirFiles(baseDir, txnFileNameFilter);
        File dir = new File(baseDir);
        try {
            if (files.length == 0) {
                // Do nothing
            } else if (files.length > 1) {
                throw new HyracksDataException("Found more than one transaction");
            } else {
                Files.delete(Paths.get(dir.getPath() + File.separator + files[0]));
            }
        } catch (IOException e) {
            throw new HyracksDataException("Failed to recover transaction", e);
        }
    }

    protected class ComparableFileName implements Comparable<ComparableFileName> {
        public final FileReference fileRef;
        public final String fullPath;
        public final String fileName;

        // Timestamp interval.
        public final String[] interval;

        public ComparableFileName(FileReference fileRef) {
            this.fileRef = fileRef;
            this.fullPath = fileRef.getFile().getAbsolutePath();
            this.fileName = fileRef.getFile().getName();
            interval = fileName.split(DELIMITER);
        }

        @Override
        public int compareTo(ComparableFileName b) {
            int startCmp = interval[0].compareTo(b.interval[0]);
            if (startCmp != 0) {
                return startCmp;
            }
            return b.interval[1].compareTo(interval[1]);
        }
    }

    private class RecencyComparator implements Comparator<ComparableFileName> {
        @Override
        public int compare(ComparableFileName a, ComparableFileName b) {
            int cmp = -a.interval[0].compareTo(b.interval[0]);
            if (cmp != 0) {
                return cmp;
            }
            return -a.interval[1].compareTo(b.interval[1]);
        }
    }

    // This function is used to delete transaction files for aborted transactions
    @Override
    public void deleteTransactionFiles() throws HyracksDataException {
        String[] files = listDirFiles(baseDir, txnFileNameFilter);
        if (files.length == 0) {
            // Do nothing
        } else if (files.length > 1) {
            throw new HyracksDataException("Found more than one transaction");
        } else {
            File dir = new File(baseDir);
            //create transaction filter
            FilenameFilter transactionFilter = createTransactionFilter(files[0], true);
            String[] componentsFiles = listDirFiles(baseDir, transactionFilter);
            for (String fileName : componentsFiles) {
                try {
                    String absFileName = dir.getPath() + File.separator + fileName;
                    Files.delete(Paths.get(absFileName));
                } catch (IOException e) {
                    throw new HyracksDataException("Failed to delete transaction files", e);
                }
            }
            // delete the txn lock file
            String absFileName = dir.getPath() + File.separator + files[0];
            try {
                Files.delete(Paths.get(absFileName));
            } catch (IOException e) {
                throw new HyracksDataException("Failed to delete transaction files", e);
            }
        }
    }

    @Override
    public LSMComponentFileReferences getNewTransactionFileReference() throws IOException {
        return null;
    }

    @Override
    public LSMComponentFileReferences getTransactionFileReferenceForCommit() throws HyracksDataException {
        return null;
    }

    protected static FilenameFilter createTransactionFilter(String transactionFileName, final boolean inclusive) {
        final String timeStamp =
                transactionFileName.substring(transactionFileName.indexOf(TXN_PREFIX) + TXN_PREFIX.length());
        return new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                if (inclusive) {
                    return name.startsWith(timeStamp);
                } else {
                    return !name.startsWith(timeStamp);
                }
            }
        };
    }

    protected FilenameFilter getTransactionFileFilter(boolean inclusive) throws HyracksDataException {
        String[] files = listDirFiles(baseDir, txnFileNameFilter);
        if (files.length == 0) {
            return dummyFilter;
        } else {
            return createTransactionFilter(files[0], inclusive);
        }
    }

    protected FilenameFilter getCompoundFilter(final FilenameFilter filter1, final FilenameFilter filter2) {
        return new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return (filter1.accept(dir, name) && filter2.accept(dir, name));
            }
        };
    }

    /**
     * @return The string format of the current timestamp.
     *         The returned results of this method are guaranteed to not have duplicates.
     */
    protected String getCurrentTimestamp() {
        Date date = new Date();
        String ts = formatter.format(date);
        /**
         * prevent a corner case where the same timestamp can be given.
         */
        while (prevTimestamp != null && ts.compareTo(prevTimestamp) == 0) {
            try {
                Thread.sleep(1);
                date = new Date();
                ts = formatter.format(date);
            } catch (InterruptedException e) {
                //ignore
            }
        }
        prevTimestamp = ts;
        return ts;
    }
}
