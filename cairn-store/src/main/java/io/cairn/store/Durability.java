package io.cairn.store;

/**
 * When the log reaches the disk.
 *
 * <p>Exposed as a choice rather than decided once because the cost is enormous and measured: an
 * fsync per record is roughly two orders of magnitude slower than none on the hardware in this
 * project's README, and the right point on that curve depends on what the operator is willing to
 * lose. What is not a choice is the ordering: a command is never reported as applied before the
 * mode's promise has been kept.
 */
public enum Durability {

    /**
     * Every append is forced to the disk before it returns.
     *
     * <p>The only mode under which an acknowledged command survives a power loss. Also the only
     * mode whose throughput is bounded by the disk rather than by the CPU, which is why the
     * benchmark in the README reports all three.
     */
    SYNC_EACH,

    /**
     * Appends are buffered; the caller decides when to force them.
     *
     * <p>For a server that batches: append a group, force once, then answer all of them. The
     * promise is per batch rather than per command, and the window of loss is whatever has not
     * been forced.
     */
    SYNC_ON_DEMAND,

    /**
     * Never force.
     *
     * <p>For benchmarks and for tests that are measuring something else. Recorded here rather than
     * left as an undocumented flag because a store that silently does not persist is the kind of
     * thing that ends up in production once.
     */
    NONE
}
