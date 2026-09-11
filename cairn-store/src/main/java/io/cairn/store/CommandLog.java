package io.cairn.store;

import io.cairn.core.Command;
import java.io.Closeable;
import java.util.List;

/**
 * An append-only sequence of commands, numbered from 1.
 *
 * <p>The log is the registry. Everything else — the current state, a snapshot, the effect
 * watermark — is derived from it by folding {@link io.cairn.core.Kernel#apply} over it, which is
 * why {@code cairnctl replay --at} exists and why an operator can answer "how did this version get
 * into production" without a separate audit table that could disagree.
 *
 * <p>Implementations are not thread-safe. A single thread owns the append path, which is a
 * contract stated once here rather than a lock taken on every method.
 */
public interface CommandLog extends Closeable {

    /**
     * Appends a command and returns the index it was given.
     *
     * <p>Whether this has reached the disk when it returns depends on {@link Durability}. Nothing
     * else in the system may report a command as applied before the mode's promise has been kept.
     */
    long append(Command command);

    /** Forces everything appended so far to the disk. */
    void sync();

    /** The highest index in the log, or 0 if it is empty. */
    long lastIndex();

    /** The lowest index still present; higher than 1 once a prefix has been discarded. */
    long firstIndex();

    /**
     * Reads a contiguous run of records.
     *
     * @param fromIndex the first index to read, inclusive
     * @param maxRecords the most to return
     * @throws StoreException if {@code fromIndex} is below {@link #firstIndex()}
     */
    List<LogRecord> read(long fromIndex, int maxRecords);

    /**
     * Releases as much of the prefix at or below {@code throughIndex} as the implementation can.
     *
     * <p>Called after a snapshot at that index has been forced to the disk, and never before: the
     * ordering is the whole content of the operation. A snapshot is a claim that the prefix is no
     * longer needed, so writing the claim down has to happen first.
     *
     * <p><b>What is guaranteed, and what is not.</b> Afterwards, no record <i>above</i>
     * {@code throughIndex} has been discarded, and {@link #firstIndex()} reports what actually
     * remains. An implementation may keep more of the prefix than it was asked to: a segmented log
     * can only free whole files, so a segment that straddles the boundary stays, and with it every
     * record below the boundary that shares it. Callers must therefore read from
     * {@link #firstIndex()} rather than from {@code throughIndex + 1}.
     *
     * <p>This wording is the result of a differential test between the file and in-memory
     * implementations. The original contract said "discards every record at or below", the
     * in-memory log honoured it exactly, the segmented one could not, and the two disagreed about
     * {@code firstIndex()} on the first seed that discarded anything. The contract was wrong rather
     * than either implementation — a log that had to free records at an arbitrary boundary would
     * have to rewrite a file, and rewriting is the one thing an append-only log does not do.
     */
    void discardThrough(long throughIndex);

    /** Total bytes the log occupies, for the metric the operations guide says to watch. */
    long sizeBytes();
}
