package io.cairn.effects;

import io.cairn.core.Command;
import io.cairn.core.Outcome;
import io.cairn.core.RegistryView;
import io.cairn.core.SequencedEffect;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves effects from the registry's outbox to the outside world, and records how far it got by
 * proposing a command.
 *
 * <p>Every part of that sentence is a decision.
 *
 * <p><b>From the outbox, not from the apply path.</b> The registry this project is derived from
 * published to Kafka inside its state machine's apply method. A replica replaying its log after a
 * restart therefore republished every event it had ever published, and a publish that failed was
 * lost while the state change that caused it stayed. Here the apply path produces a value and stops;
 * delivery is a separate activity that can fail, retry, and fall behind without any of that
 * reaching the state machine.
 *
 * <p><b>In order.</b> The outbox is contiguous and sorted, delivery follows it, and a failure stops
 * the run rather than skipping ahead. That is what lets a consumer deduplicate on a single number
 * instead of a set that grows forever — and it is what makes the demotion of an incumbent
 * production version arrive before the promotion of its successor, so a consumer replaying the
 * stream never holds two.
 *
 * <p><b>By proposing a command.</b> The watermark is replicated state, moved by
 * {@link Command.AckEffects} going through the log like anything else. A dispatcher that kept its
 * own counter would, on a crash, either redeliver — tolerable — or, if it advanced the counter
 * before delivering, skip. Making the watermark part of the state costs one log record per batch
 * and removes that choice.
 *
 * <p>Single-writer by construction: the dispatcher runs in the process that owns the log, so there
 * is no lease to acquire and no fencing token to check. {@code docs/design/0003-effects-are-values.md}
 * says what would have to change for that not to be true.
 */
public final class Dispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(Dispatcher.class);

    /** How a dispatcher gets a command into the log. */
    @FunctionalInterface
    public interface Proposer {
        /** Appends and applies a command, returning what the kernel said. */
        Outcome propose(Command command);
    }

    /**
     * What one run did.
     *
     * @param delivered effects handed to the sink and accepted by it
     * @param acknowledged the sequence number committed, or 0 if nothing was
     * @param pending effects still in the outbox afterwards
     * @param failure why the run stopped early, or null if it drained the outbox
     */
    public record Run(int delivered, long acknowledged, int pending, RuntimeException failure) {

        /** Whether the outbox was drained. */
        public boolean complete() {
            return failure == null && pending == 0;
        }
    }

    private final Supplier<RegistryView> state;
    private final EffectSink sink;
    private final Proposer proposer;

    private long delivered;
    private long failures;
    private long runs;

    public Dispatcher(Supplier<RegistryView> state, EffectSink sink, Proposer proposer) {
        this.state = state;
        this.sink = sink;
        this.proposer = proposer;
    }

    /**
     * Delivers everything pending, stopping at the first refusal, then acknowledges the prefix that
     * got through.
     *
     * <p>One acknowledgement per run rather than one per effect: the watermark is monotone, so
     * committing only the highest is equivalent and costs one log record instead of a hundred. A
     * crash before the acknowledgement redelivers the whole run, which the consumer's ledger
     * absorbs.
     */
    public Run drain() {
        runs++;
        List<SequencedEffect> pending = state.get().outbox();
        if (pending.isEmpty()) {
            return new Run(0, 0, 0, null);
        }

        int count = 0;
        long highest = 0;
        RuntimeException failure = null;
        for (SequencedEffect effect : pending) {
            try {
                sink.deliver(effect);
                highest = effect.seq();
                count++;
                delivered++;
            } catch (RuntimeException e) {
                // Stop here, not skip. The next effect may depend on this one having landed, and
                // acknowledging past a gap would lose it permanently.
                failures++;
                failure = e;
                LOG.warn("sink {} refused effect #{} ({}); {} delivered this run, stopping",
                        sink.name(), effect.seq(), e.getMessage(), count);
                break;
            }
        }

        long acknowledged = 0;
        if (highest > 0) {
            Outcome outcome = proposer.propose(new Command.AckEffects(highest));
            if (outcome.accepted()) {
                acknowledged = highest;
            } else {
                // The kernel refused the acknowledgement, which means the dispatcher and the
                // registry disagree about which registry this is: two writers, or a restore from
                // an older snapshot. Loud, and the effects stay in the outbox.
                LOG.error("the registry refused an acknowledgement through #{}: {}",
                        highest, outcome);
            }
        }

        int remaining = state.get().outbox().size();
        return new Run(count, acknowledged, remaining, failure);
    }

    /**
     * Drains repeatedly until the outbox is empty or a run makes no progress.
     *
     * <p>Bounded by progress rather than by a retry count: a run that delivers nothing and fails is
     * not going to do better on the next attempt within the same call, and looping on it would turn
     * a broken sink into a busy wait. The caller retries later, on its own schedule.
     */
    public Run drainAll() {
        Run last = drain();
        while (last.delivered() > 0 && last.pending() > 0 && last.failure() == null) {
            last = drain();
        }
        return last;
    }

    /** Total effects delivered across every run. */
    public long deliveredCount() {
        return delivered;
    }

    /** Total refusals from the sink. */
    public long failureCount() {
        return failures;
    }

    /** How many times {@link #drain} has run. */
    public long runCount() {
        return runs;
    }

    /**
     * How far behind delivery is.
     *
     * <p>The number to alert on. A registry whose outbox is growing is one whose artifacts are not
     * being collected and whose caches are not being invalidated, and every other metric looks
     * healthy while it happens.
     */
    public int lag() {
        return state.get().outbox().size();
    }
}
