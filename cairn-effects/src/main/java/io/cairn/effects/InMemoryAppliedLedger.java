package io.cairn.effects;

/**
 * An {@link AppliedLedger} in memory.
 *
 * <p>Correct for a consumer whose downstream state is also in memory — a cache, for instance,
 * which loses its contents on a restart and therefore has nothing to deduplicate against. Wrong
 * for one whose downstream state is durable, because then a restart forgets the watermark while
 * the effects it describes are still applied, and every one of them is applied a second time.
 * Saying so here rather than in a README because this is the class somebody will reach for first.
 */
public final class InMemoryAppliedLedger implements AppliedLedger {

    private long applied;

    @Override
    public long applied() {
        return applied;
    }

    @Override
    public void record(long seq) {
        if (seq < applied) {
            throw new IllegalArgumentException(
                    "the applied watermark must not move backwards: " + applied + " -> " + seq);
        }
        applied = seq;
    }

    @Override
    public void close() {
        // Nothing to release.
    }
}
