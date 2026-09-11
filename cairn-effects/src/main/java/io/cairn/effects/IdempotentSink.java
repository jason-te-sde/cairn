package io.cairn.effects;

import io.cairn.core.SequencedEffect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns an at-least-once stream into at-most-once application.
 *
 * <p>Four lines of logic, and it is the half of exactly-once that the registry cannot supply on its
 * own. The dispatcher will offer an effect again after any crash that left its acknowledgement
 * uncommitted; this discards an offer at or below what the ledger already records, and only
 * advances the ledger once the wrapped sink has returned.
 *
 * <p>The ordering is the content. Deliver, then record. Recording first would mean a crash between
 * the two skipped the effect forever, and an effect that orders an artifact deleted or a cache
 * invalidated is not something to skip quietly. Delivering first means a crash between the two
 * redelivers, which is the failure this class exists to absorb.
 *
 * <p>Counting duplicates rather than ignoring them, because a duplicate rate that is not zero is
 * the number that tells an operator the dispatcher is crashing between delivery and
 * acknowledgement — a fault that is invisible in every other metric precisely because the system
 * handles it correctly.
 */
public final class IdempotentSink implements EffectSink {

    private static final Logger LOG = LoggerFactory.getLogger(IdempotentSink.class);

    private final EffectSink delegate;
    private final AppliedLedger ledger;
    private long duplicates;
    private long applied;

    public IdempotentSink(EffectSink delegate, AppliedLedger ledger) {
        this.delegate = delegate;
        this.ledger = ledger;
    }

    @Override
    public void deliver(SequencedEffect effect) {
        long alreadyApplied = ledger.applied();
        if (effect.seq() <= alreadyApplied) {
            duplicates++;
            LOG.debug("dropping effect #{}, already applied through #{}",
                    effect.seq(), alreadyApplied);
            return;
        }
        delegate.deliver(effect);
        ledger.record(effect.seq());
        applied++;
    }

    /** How many effects this sink has actually applied. */
    public long appliedCount() {
        return applied;
    }

    /**
     * How many redeliveries have been discarded.
     *
     * <p>Non-zero is normal and is worth a dashboard: it counts the crashes that happened between a
     * delivery and its acknowledgement, which nothing else in the system can see.
     */
    public long duplicateCount() {
        return duplicates;
    }

    /** The highest sequence number applied. */
    public long watermark() {
        return ledger.applied();
    }

    @Override
    public String name() {
        return "idempotent(" + delegate.name() + ")";
    }
}
