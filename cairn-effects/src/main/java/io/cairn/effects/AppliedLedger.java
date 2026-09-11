package io.cairn.effects;

import java.io.Closeable;

/**
 * A consumer's durable memory of the highest effect it has applied.
 *
 * <p>One number is enough state for the whole stream, and that is not an optimization — it is the
 * reason this design works at all. Effects are delivered in sequence order, so "I have applied
 * everything up to 4,271" is a complete description of what a consumer has seen. A deduplication
 * scheme over unordered deliveries needs a set that grows forever, or a window that silently stops
 * protecting you once it slides past the duplicate.
 *
 * <p>The ordering an implementation must respect is: record the sequence number <b>after</b> the
 * effect has taken hold downstream, and durably. Recording it first turns a crash into a skipped
 * effect, which is the failure nobody notices until an artifact is never collected or a cache
 * serves a retired model for a week.
 */
public interface AppliedLedger extends Closeable {

    /** The highest sequence number applied, or 0 if none. */
    long applied();

    /**
     * Records that everything up to and including {@code seq} has been applied.
     *
     * <p>Must be durable when it returns, and must not move backwards.
     */
    void record(long seq);
}
