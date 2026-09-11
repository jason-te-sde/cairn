package io.cairn.core;

/**
 * Why the kernel refused a command.
 *
 * <p>Every code here is about *state*: what the registry already holds makes the command
 * impossible. None of them is about syntax, because a malformed identifier cannot be constructed —
 * {@link ModelId} and its neighbours validate in their constructors — so by the time a command
 * exists it is well-formed and only the world can be wrong.
 *
 * <p>The split matters for the HTTP layer, which maps a construction failure to 400 and a rejection
 * to 409 or 404: "you sent nonsense" and "you sent something reasonable that conflicts with
 * reality" are different problems for the caller, and collapsing them into one status is how a
 * client ends up retrying something that will never succeed.
 */
public enum RejectionCode {

    /**
     * No artifact with that digest has been ingested, or it has been collected.
     *
     * <p>The second case is the interesting one. An artifact whose reference count reached zero is
     * marked absent in the same transition that ordered its bytes deleted, so a publish naming it
     * is refused here rather than succeeding and leaving a version pointing at a file that a
     * collector is on its way to remove.
     */
    ARTIFACT_MISSING,

    /**
     * That version exists with different content.
     *
     * <p>A republish with the *same* digest and parents is not this: it is an accepted retry, and
     * reports {@link Outcome.Applied.Kind#IDEMPOTENT}. Only a genuine attempt to change what a
     * published version means lands here.
     */
    IMMUTABLE_VERSION,

    /** The same digest was ingested before with a different length, so one of the two is wrong. */
    DIGEST_SIZE_MISMATCH,

    /**
     * The artifact is on its way out and cannot come back yet.
     *
     * <p>Its reference count reached zero, a collection order was produced, and that order has not
     * been delivered. Re-ingesting now would store bytes that a committed instruction says to
     * delete, and the delete would win — leaving a version pointing at nothing. Retry once the
     * effect watermark has passed the order; {@code docs/operations.md} says how to see where it is.
     *
     * <p>Found by the simulator rather than by design: the sequence <i>delete, re-ingest, publish,
     * collector finally runs</i> produced a dangling reference, and nothing in the state accounted
     * for an effect that had been decided but not yet carried out.
     */
    COLLECTION_PENDING,

    /** A declared parent names a model the registry has never heard of. */
    UNKNOWN_PARENT,

    /** A declared parent exists but has been tombstoned, so the lineage would dangle. */
    PARENT_DELETED,

    /** A version declared itself as its own parent. */
    SELF_PARENT,

    /** No such model. */
    UNKNOWN_MODEL,

    /** No such version of that model. */
    UNKNOWN_VERSION,

    /** The version is a tombstone; nothing may be done to it. */
    VERSION_DELETED,

    /** {@link Stage#canTransitionTo} says no. */
    ILLEGAL_TRANSITION,

    /**
     * The version is in production.
     *
     * <p>Deleting it is refused rather than cascaded. Demoting it first is one extra command and it
     * is the command that makes "production is now nothing" a decision somebody took, rather than
     * a side effect of a cleanup script.
     */
    PRODUCTION_VERSION,

    /**
     * Other versions declare this one as an ancestor.
     *
     * <p>Deleting it would leave their lineage pointing at nothing, which is the provenance
     * equivalent of a dangling artifact. Archiving or deprecating it is always available.
     */
    HAS_DESCENDANTS,

    /**
     * An acknowledgement claimed to have delivered effects the kernel has never produced.
     *
     * <p>Either the dispatcher is talking to a registry that is behind it — two writers, or a
     * restore from an old snapshot — or its own watermark is corrupt. Both are serious enough that
     * silently clamping the value would hide them.
     */
    ACK_AHEAD_OF_LOG
}
