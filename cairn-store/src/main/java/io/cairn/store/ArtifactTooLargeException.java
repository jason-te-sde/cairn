package io.cairn.store;

/**
 * The artifact is larger than this store will accept.
 *
 * <p>Its own type because the right HTTP answer is <b>413</b>, and it used to come back as a
 * generic 409 with the limit buried in a prose message. A client cannot act on that: 409 says "this
 * conflicts with reality" and invites a retry, while 413 says "not like this, ever" and names the
 * ceiling.
 *
 * <p>Detected while streaming rather than from a {@code Content-Length}, so it is what happened
 * rather than what the client claimed. The partial write is removed before this is thrown.
 *
 * @param written how many bytes had arrived when the limit was passed
 * @param limit the ceiling
 */
public final class ArtifactTooLargeException extends StoreException {

    private final long written;
    private final long limit;

    public ArtifactTooLargeException(long written, long limit) {
        super("artifact exceeds the " + limit + " byte limit; at least " + written
                + " bytes were offered");
        this.written = written;
        this.limit = limit;
    }

    /** How many bytes had arrived when the limit was passed. */
    public long written() {
        return written;
    }

    /** The ceiling that was exceeded. */
    public long limit() {
        return limit;
    }
}
