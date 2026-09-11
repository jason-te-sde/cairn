package io.cairn.core;

/**
 * A thing that applies commands and can be asked what it holds.
 *
 * <p>Exists so that the simulator and the invariant checker can be pointed at more than one
 * implementation of the rules. {@link PureKernel} wraps the real one; the testkit holds a second
 * written independently, and a family of deliberately broken ones. Code that drives a registry
 * should take this rather than {@link Kernel}, which is what makes the differential test and the
 * flaw injection possible without a line of test scaffolding in production code.
 */
public interface RegistryKernel {

    /**
     * Applies one command at one log index.
     *
     * @param index the 1-based, contiguous log index
     * @param command what to do
     * @return what happened
     */
    Outcome apply(long index, Command command);

    /** What the registry holds now. */
    RegistryView view();

    /** A short name for failure messages, so a differential failure says which side was wrong. */
    String name();
}
