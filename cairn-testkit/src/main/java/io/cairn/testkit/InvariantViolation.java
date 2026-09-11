package io.cairn.testkit;

/**
 * A property that is supposed to always hold did not.
 *
 * <p>Carries the seed and the step, because a failure that cannot be replayed is close to
 * worthless. The message is built to be pasted back into a test as-is.
 */
public final class InvariantViolation extends AssertionError {

    private final String invariant;
    private final long seed;
    private final long step;

    public InvariantViolation(String invariant, long seed, long step, String detail) {
        super(invariant + " violated at step " + step + " (seed " + seed + "): " + detail);
        this.invariant = invariant;
        this.seed = seed;
        this.step = step;
    }

    /** Which property failed, by the name used in {@code docs/testing.md}. */
    public String invariant() {
        return invariant;
    }

    /** The seed that reproduces the run. */
    public long seed() {
        return seed;
    }

    /** The step within the run. */
    public long step() {
        return step;
    }
}
