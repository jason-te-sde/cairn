package io.cairn.core;

/**
 * What the registry knows about one artifact.
 *
 * <p>Note what is absent: a path, a bucket, a URL. The registry records that an artifact with this
 * digest and this length was ingested and verified, and nothing about where the bytes live. A blob
 * store derives its own layout from the digest, so moving from a local directory to object storage
 * changes no replicated state and needs no migration.
 *
 * <p>{@code refCount} is the number of live versions whose artifact is this digest. It is
 * denormalized — it could be recomputed by walking every version — and that is a deliberate
 * exception to the rule applied elsewhere in this package, because the alternative is an O(versions)
 * walk on the delete path to decide whether an artifact is collectable. The exception is paid for:
 * invariant I3 recomputes it from scratch after every step of every simulation and fails if the two
 * disagree.
 *
 * <p>{@code collectSeq} is the subtler field, and it exists because of a hole a simulation found.
 * An {@link Effect.ArtifactCollected} sitting in the outbox is an instruction to delete bytes that
 * has not been carried out yet. Without this field the following was legal: delete the last version
 * referencing an artifact, re-ingest the same bytes, publish a new version against them — and then
 * the collector finally runs and deletes the artifact out from under the new version. So a
 * collection order records its own sequence number here, and the artifact cannot come back until
 * the watermark has passed it. An artifact that is {@code present} therefore always has
 * {@code collectSeq == 0}, which the constructor enforces.
 *
 * @param digest the content address
 * @param size length in bytes, as measured while writing
 * @param refCount number of live versions referencing this artifact
 * @param present whether the artifact is believed to be retrievable
 * @param collectSeq sequence number of the outstanding collection order, or 0 if there is none
 */
public record Blob(Digest digest, long size, int refCount, boolean present, long collectSeq) {

    public Blob {
        if (digest == null) {
            throw new IllegalArgumentException("digest must not be null");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative: " + size);
        }
        if (size > Limits.MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException(
                    "size exceeds the " + Limits.MAX_ARTIFACT_BYTES + " byte limit: " + size);
        }
        if (refCount < 0) {
            throw new IllegalArgumentException("refCount must not be negative: " + refCount);
        }
        if (collectSeq < 0) {
            throw new IllegalArgumentException("collectSeq must not be negative: " + collectSeq);
        }
        if (!present && refCount > 0) {
            // An absent artifact with live references is a dangling pointer, and the point of the
            // whole delete path is that it cannot happen. Catching it here as well means a future
            // change that breaks the invariant fails at the moment it constructs the bad value,
            // with a stack trace that names the caller, rather than at the next invariant check.
            throw new IllegalArgumentException(
                    "artifact " + digest.shortHex() + " is absent but still referenced "
                            + refCount + " times");
        }
        if (present && collectSeq != 0) {
            throw new IllegalArgumentException(
                    "artifact " + digest.shortHex() + " is present with an outstanding collection"
                            + " order at #" + collectSeq + "; the collector would delete it");
        }
    }

    /** A freshly ingested artifact: retrievable, referenced by nothing, not being collected. */
    public static Blob ingested(Digest digest, long size) {
        return new Blob(digest, size, 0, true, 0);
    }

    Blob withRefCount(int newCount) {
        return new Blob(digest, size, newCount, present, collectSeq);
    }

    /**
     * Marks the artifact absent and records which collection order covers it.
     *
     * @param orderSeq the sequence number of the {@link Effect.ArtifactCollected} being produced
     */
    Blob collecting(long orderSeq) {
        if (orderSeq < 1) {
            throw new IllegalArgumentException("a collection order needs a sequence number");
        }
        return new Blob(digest, size, 0, false, orderSeq);
    }

    /** Marks the artifact retrievable again, cancelling nothing: the order has already run. */
    Blob reingested() {
        return new Blob(digest, size, refCount, true, 0);
    }

    /** Whether this artifact may be collected: retrievable now, and referenced by nothing. */
    public boolean collectable() {
        return present && refCount == 0;
    }

    /**
     * Whether the bytes may be written again.
     *
     * <p>False while a collection order for this artifact is still in the outbox: the collector has
     * been told to delete these bytes and has not done it yet, so storing them again now would race
     * a deletion that is already committed.
     *
     * @param dispatchedThrough the registry's effect watermark
     */
    public boolean reingestable(long dispatchedThrough) {
        return !present && (collectSeq == 0 || dispatchedThrough >= collectSeq);
    }
}
