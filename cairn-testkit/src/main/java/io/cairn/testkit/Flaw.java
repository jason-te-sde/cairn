package io.cairn.testkit;

/**
 * A mistake this project is built not to make, put back on purpose.
 *
 * <p>A test suite that has only ever run against correct code has proved nothing about itself. So
 * each of these reintroduces a specific defect, and {@code FlawTest} asserts that a named check
 * catches it — the {@link #caughtBy} field is the assertion, not a comment, so a change that
 * weakens a check fails at the flaw it was supposed to catch rather than passing quietly.
 *
 * <p><b>Five of these are real defects in the system this project is derived from</b>, a
 * Raft-backed AI model registry whose state machine stored a version record as
 * {@code join(":")} and read it back with {@code split(":", 7)}, published to Kafka from inside
 * its apply method, wrapped that apply method in {@code catch (Exception e) { LOG.error(...) }},
 * restored snapshots by pointing its live database handle at the checkpoint directory, and stored
 * a client-supplied {@code fileHash} it never verified. Those are marked {@link #inherited}. The
 * other three are mistakes a competent implementation of these rules could plausibly make.
 */
public enum Flaw {

    /**
     * Serialize a version record by joining its fields with a colon.
     *
     * <p>The original. A label value or description containing a colon — {@code s3://bucket/key},
     * or any URL — moves every field after it, so the record reads back as a different record. The
     * fix is two independent defences: identifiers cannot contain a delimiter, and the encoding has
     * no delimiter to contain.
     */
    COLON_CODEC(true, "a colon-delimited record format",
            "ColonCodecTest: a label value containing a colon round-trips to a different record"),

    /**
     * Publish effects from inside the apply path.
     *
     * <p>The original, and the defect this whole project is organized around. A replica replaying
     * its log after a restart republishes every event it has ever published, and a publish that
     * fails is lost while the state change that caused it is durable.
     */
    EFFECTS_IN_APPLY(true, "side effects performed during apply instead of appended to the outbox",
            "I7 Exactly-once delivery"),

    /**
     * Catch and log an error in apply, then carry on.
     *
     * <p>The original: {@code catch (Exception e) { LOG.error(...) }} around a state machine's
     * apply method. A replica that hits the error path skips the mutation and stays in the cluster,
     * so two replicas holding different state both look healthy.
     */
    SWALLOW_APPLY_ERRORS(true, "an apply failure swallowed and reported as success",
            "I1 Convergence"),

    /**
     * Restore a snapshot by pointing live storage at the snapshot itself.
     *
     * <p>The original: {@code readSnap} closed the live RocksDB and reopened it on the checkpoint
     * directory. Every write after a restore landed inside the snapshot, and the next restore lost
     * them.
     *
     * <p>Where it is caught turned out to be more interesting than expected. On its own the defect
     * is <i>survivable</i>: a snapshot that under-reports its own index costs a longer replay and
     * nothing else, because the log still holds the records. It becomes data loss only once the log
     * is compacted on the strength of a snapshot that does not cover what it claims — and then
     * {@link io.cairn.store.Recovery} refuses to start, which is the correct outcome and a better
     * one than an invariant firing later. The first attempt at this test asserted I8 Monotonicity
     * and never fired, because the state never actually went backwards.
     */
    SNAPSHOT_ALIASES_LIVE_STATE(true, "a restore that makes later writes land inside the snapshot",
            "Recovery: the log has been trimmed past what any usable snapshot reaches"),

    /**
     * Store the digest the client sent instead of the one the bytes have.
     *
     * <p>The original: a {@code fileHash} field taken from the request and stored, with nothing
     * ever hashing the file.
     */
    TRUST_CLIENT_DIGEST(true, "a client-supplied digest stored without verifying the bytes",
            "I11 Artifact integrity"),

    /**
     * Let a republish overwrite what a version means.
     *
     * <p>Not inherited — the original had no notion of an immutable version at all, which is a
     * missing feature rather than a bug in one. This is the mistake an implementation that
     * <i>does</i> intend immutability makes: treating a second publish as an update.
     */
    OVERWRITE_ON_REPUBLISH(false, "a republish that replaces the artifact of an existing version",
            "I2 Version immutability"),

    /**
     * Collect an artifact whenever a version referencing it is deleted.
     *
     * <p>The natural mistake: deletion and collection look like the same operation until two
     * versions share an artifact, which in a model registry is the common case rather than the
     * exception.
     *
     * <p>Worth noting where this one is caught. Not by an invariant — by {@code Blob}'s own
     * constructor, which refuses to build an absent artifact that live versions still reference.
     * That is the better outcome: a bug caught by making the bad state unrepresentable fails at the
     * line that caused it, with a stack trace naming the caller, rather than at the next check.
     * I3 recomputes every reference count from scratch several thousand times per simulation
     * anyway, so it is not short of exercise.
     */
    COLLECT_WITHOUT_COUNTING(false, "an artifact collected without checking for other references",
            "Blob's constructor: an absent artifact with live references is not a representable"
                    + " value"),

    /**
     * Record an effect as applied before applying it.
     *
     * <p>The ordering mistake in a consumer. It makes a crash between the two lose the effect
     * rather than repeat it, which is the wrong direction to fail in: a repeat is absorbed by the
     * watermark, a loss is permanent and silent.
     */
    WATERMARK_BEFORE_DELIVERY(false, "a consumer watermark advanced before the delivery lands",
            "I7 Exactly-once delivery");

    private final boolean inherited;
    private final String description;
    private final String caughtBy;

    Flaw(boolean inherited, String description, String caughtBy) {
        this.inherited = inherited;
        this.description = description;
        this.caughtBy = caughtBy;
    }

    /** Whether this defect is one the system this project is derived from actually had. */
    public boolean inherited() {
        return inherited;
    }

    /** What the flaw does, in one line. */
    public String description() {
        return description;
    }

    /**
     * Which check is supposed to catch it.
     *
     * <p>Asserted rather than documented: {@code FlawTest} enables the flaw and requires the
     * failure to name this check, so a check that stops working fails here.
     */
    public String caughtBy() {
        return caughtBy;
    }
}
