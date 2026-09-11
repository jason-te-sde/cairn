package io.cairn.store;

import io.cairn.core.Digest;
import java.io.Closeable;
import java.io.InputStream;
import java.util.List;

/**
 * Where artifact bytes live, addressed by what they hash to.
 *
 * <p>There is no {@code put(digest, bytes)} on this interface, and its absence is the design. A
 * store that is told what an artifact hashes to has to trust the caller; a store that computes the
 * hash as the bytes go past does not, and {@link #put} returns the digest rather than accepting
 * one. {@link #putVerified} exists for the case where the caller has a digest to check against,
 * and it checks rather than believes.
 */
public interface BlobStore extends Closeable {

    /** An artifact that has been written, with what it turned out to be. */
    record Ingested(Digest digest, long size) {}

    /**
     * Writes an artifact, computing its digest as it goes.
     *
     * <p>Content-addressed, so writing the same bytes twice is one artifact. The stream is
     * consumed but not closed: the caller opened it.
     */
    Ingested put(InputStream bytes);

    /**
     * Writes an artifact and refuses it unless it hashes to {@code expected}.
     *
     * @throws DigestMismatchException if the bytes hash to anything else, having stored nothing
     */
    Ingested putVerified(Digest expected, InputStream bytes);

    /**
     * Opens an artifact for reading.
     *
     * @throws NoSuchBlobException if it is not here
     */
    InputStream open(Digest digest);

    /** Whether an artifact is here. */
    boolean contains(Digest digest);

    /** Length of a stored artifact in bytes. */
    long size(Digest digest);

    /**
     * Removes an artifact.
     *
     * @return whether it was there to remove
     */
    boolean delete(Digest digest);

    /**
     * Every artifact in the store.
     *
     * <p>Used by {@code cairnctl fsck} to compare the disk against the registry. Note which
     * direction that comparison runs: the registry decides what should exist, and a blob the
     * registry does not know about is reported rather than deleted, because a sweep that deletes
     * whatever it cannot find a reference for races every publish in flight.
     */
    List<Digest> list();

    /** Total bytes stored. */
    long totalBytes();
}
