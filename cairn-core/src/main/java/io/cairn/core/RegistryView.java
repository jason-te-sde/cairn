package io.cairn.core;

import java.util.List;
import java.util.Optional;
import java.util.SortedMap;

/**
 * Everything an observer may see, and nothing about how it is kept.
 *
 * <p>This interface is what makes the test suite able to compare two implementations of the rules.
 * {@link Registry} is one implementation — immutable records, structural sharing — and the testkit
 * holds a second written for clarity rather than for sharing, using mutable maps and straightforward
 * imperative logic. Both project to this, so the differential test can run the same command stream
 * through both and require the projections to be equal after every step, and the invariant checker
 * can be pointed at either.
 *
 * <p>That second part is the one that pays. A checker that only ever runs against the correct
 * implementation proves nothing about the checker; running it against implementations with known
 * bugs put back on purpose is how the suite is shown to be capable of going red.
 */
public interface RegistryView {

    /** Every model, by name. */
    SortedMap<ModelId, Model> models();

    /** Every artifact the registry has recorded, by content address. */
    SortedMap<Digest, Blob> blobs();

    /**
     * Effects produced and not yet acknowledged, in sequence order.
     *
     * <p>Bounded by how far the dispatcher is behind, not by how much has ever happened: the
     * acknowledged prefix is dropped. A growing outbox therefore means delivery has stopped, which
     * is why it is the number {@code docs/operations.md} tells you to alert on.
     */
    List<SequencedEffect> outbox();

    /** The sequence number the next effect will get. Starts at 1. */
    long nextEffectSeq();

    /** Highest effect sequence number known to be applied downstream. */
    long dispatchedThrough();

    /** Highest log index applied. */
    long appliedIndex();

    /** Convenience: one model. */
    default Optional<Model> model(ModelId id) {
        return Optional.ofNullable(models().get(id));
    }

    /** Convenience: one version, live or tombstoned. */
    default Optional<ModelVersion> version(Ref ref) {
        return model(ref.model()).flatMap(m -> m.version(ref.version()));
    }

    /** Convenience: one artifact. */
    default Optional<Blob> blob(Digest digest) {
        return Optional.ofNullable(blobs().get(digest));
    }

    /**
     * Compares the parts of two views that the rules determine.
     *
     * <p>{@link #appliedIndex()} is excluded on purpose, and the exclusion is the definition of a
     * property the kernel promises: a rejected command advances the log position and changes
     * nothing else. Comparing views that include the index would make that property unstateable,
     * since every command moves the index whether it was accepted or not.
     */
    static boolean sameState(RegistryView left, RegistryView right) {
        return left.models().equals(right.models())
                && left.blobs().equals(right.blobs())
                && left.outbox().equals(right.outbox())
                && left.nextEffectSeq() == right.nextEffectSeq()
                && left.dispatchedThrough() == right.dispatchedThrough();
    }
}
