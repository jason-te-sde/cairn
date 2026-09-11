package io.cairn.store;

/**
 * That point in history cannot be reconstructed, because the log below it has been released.
 *
 * <p>Distinct from a plain {@link StoreException} because it is not a fault. Log compaction is
 * supposed to happen, and once a prefix is gone the states below it are gone with it — asking for
 * one is a reasonable request with an honest "no longer" as the answer. The HTTP layer maps it to
 * <b>410 Gone</b> rather than 409, and carries {@link #earliestAvailable} so the caller learns how
 * far back history actually goes instead of having to bisect for it.
 *
 * <p>It is also reachable by simply being unlucky: an index that was available when a client read
 * the current position can be compacted away before the follow-up request arrives. A concurrency
 * test found exactly that, which is why the answer names a number the caller can act on.
 *
 * @param requested the index that was asked for
 * @param earliestAvailable the lowest index that can still be reconstructed
 */
public final class HistoryUnavailableException extends StoreException {

    private final long requested;
    private final long earliestAvailable;

    public HistoryUnavailableException(long requested, long earliestAvailable) {
        super("cannot reconstruct index " + requested
                + ": the log below it has been released, and the earliest index still available is "
                + earliestAvailable);
        this.requested = requested;
        this.earliestAvailable = earliestAvailable;
    }

    /** The index that was asked for. */
    public long requested() {
        return requested;
    }

    /** The lowest index that can still be reconstructed. */
    public long earliestAvailable() {
        return earliestAvailable;
    }
}
