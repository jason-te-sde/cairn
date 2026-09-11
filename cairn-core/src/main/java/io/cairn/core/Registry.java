package io.cairn.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The whole replicated state of a registry, as one immutable value.
 *
 * <p>Immutability is not a stylistic preference here; three things fall out of it that would each
 * otherwise be work:
 *
 * <ul>
 *   <li><b>Reads need no lock.</b> A server publishes a new state by assigning one reference. A
 *       reader holds a whole consistent registry for as long as it likes, and cannot see a
 *       half-applied command, because there is no such value.
 *   <li><b>A replay is a fold.</b> Reconstructing the state at log index <i>n</i> is
 *       {@code entries.stream().limit(n).reduce(empty(), apply)}, which is what makes
 *       {@code cairnctl replay --at} a few lines rather than a feature.
 *   <li><b>A rejection cannot corrupt anything.</b> The kernel builds the next state and returns
 *       it, or returns the one it was given. There is no path on which validation fails halfway
 *       through a mutation, which is the way this goes wrong in a mutable implementation.
 * </ul>
 *
 * <p>The cost is allocation: every command copies the maps on the path it touched. Structural
 * sharing keeps that proportional to the change rather than to the registry, and the throughput
 * number in the README is measured rather than assumed.
 *
 * <p>Every map is sorted. That is what makes {@code cairn-codec} able to produce one canonical
 * encoding per state, and therefore what makes two replicas comparable by a single SHA-256 rather
 * than by walking them. A {@code HashMap} here would also be a per-JVM iteration order, which is a
 * bug the two-JDK CI matrix in this project's predecessor found the hard way.
 *
 * @param models every model, by name
 * @param blobs every recorded artifact, by content address
 * @param outbox effects produced and not yet acknowledged, in sequence order
 * @param nextEffectSeq the sequence number the next effect will get
 * @param dispatchedThrough highest effect sequence number applied downstream
 * @param appliedIndex highest log index applied
 */
public record Registry(
        SortedMap<ModelId, Model> models,
        SortedMap<Digest, Blob> blobs,
        List<SequencedEffect> outbox,
        long nextEffectSeq,
        long dispatchedThrough,
        long appliedIndex) implements RegistryView {

    public Registry {
        // naturalOrder rather than new TreeMap<>(map): the latter inherits the source map's
        // comparator, so a caller holding a reverse-ordered SortedMap would get a reverse-ordered
        // registry, and every canonical encoding here assumes ascending keys.
        models = models == null
                ? Collections.emptySortedMap()
                : Collections.unmodifiableSortedMap(naturalOrder(models));
        blobs = blobs == null
                ? Collections.emptySortedMap()
                : Collections.unmodifiableSortedMap(naturalOrder(blobs));
        outbox = outbox == null ? List.of() : List.copyOf(outbox);
        if (nextEffectSeq < 1) {
            throw new IllegalArgumentException("nextEffectSeq starts at 1, got " + nextEffectSeq);
        }
        if (dispatchedThrough < 0 || dispatchedThrough >= nextEffectSeq) {
            throw new IllegalArgumentException(
                    "dispatchedThrough must be in [0, " + (nextEffectSeq - 1) + "], got "
                            + dispatchedThrough);
        }
        if (appliedIndex < 0) {
            throw new IllegalArgumentException("appliedIndex must not be negative: " + appliedIndex);
        }
    }

    /** A registry that has never had anything applied to it. */
    public static Registry empty() {
        return new Registry(null, null, null, 1, 0, 0);
    }

    /** How many effects have been produced and not yet acknowledged. */
    public int outboxDepth() {
        return outbox.size();
    }

    /** Effects waiting for delivery: everything above the watermark. */
    public List<SequencedEffect> undispatched() {
        return outbox;
    }

    private static <K extends Comparable<K>, V> TreeMap<K, V> naturalOrder(SortedMap<K, V> source) {
        TreeMap<K, V> copy = new TreeMap<>();
        copy.putAll(source);
        return copy;
    }

    // ---- Transformations used by the kernel. Package-private: the only legal way to change a
    // ---- registry from outside this package is to apply a command to it.

    Registry withAppliedIndex(long index) {
        return new Registry(models, blobs, outbox, nextEffectSeq, dispatchedThrough, index);
    }

    Registry withModel(Model model) {
        TreeMap<ModelId, Model> next = new TreeMap<>(models);
        next.put(model.id(), model);
        return new Registry(next, blobs, outbox, nextEffectSeq, dispatchedThrough, appliedIndex);
    }

    Registry withBlob(Blob blob) {
        TreeMap<Digest, Blob> next = new TreeMap<>(blobs);
        next.put(blob.digest(), blob);
        return new Registry(models, next, outbox, nextEffectSeq, dispatchedThrough, appliedIndex);
    }

    /**
     * Appends effects, assigning them the next sequence numbers.
     *
     * <p>Returns the sequenced effects as well as the new state so the caller can report them in an
     * {@link Outcome.Applied} without re-deriving which ones were new. Sequence numbers are
     * assigned here and only here.
     */
    Appended withEffects(List<Effect> produced) {
        if (produced.isEmpty()) {
            return new Appended(this, List.of());
        }
        List<SequencedEffect> sequenced = new ArrayList<>(produced.size());
        long seq = nextEffectSeq;
        for (Effect effect : produced) {
            sequenced.add(new SequencedEffect(seq++, effect));
        }
        List<SequencedEffect> nextOutbox = new ArrayList<>(outbox.size() + sequenced.size());
        nextOutbox.addAll(outbox);
        nextOutbox.addAll(sequenced);
        Registry next =
                new Registry(models, blobs, nextOutbox, seq, dispatchedThrough, appliedIndex);
        return new Appended(next, List.copyOf(sequenced));
    }

    Registry withWatermark(long throughSeq) {
        List<SequencedEffect> remaining = new ArrayList<>(outbox.size());
        for (SequencedEffect candidate : outbox) {
            if (candidate.seq() > throughSeq) {
                remaining.add(candidate);
            }
        }
        return new Registry(models, blobs, remaining, nextEffectSeq, throughSeq, appliedIndex);
    }

    /** A state with effects appended, and the sequence numbers they were given. */
    record Appended(Registry state, List<SequencedEffect> effects) {}

    @Override
    public String toString() {
        return "Registry[models=" + models.size()
                + " blobs=" + blobs.size()
                + " outbox=" + outbox.size()
                + " seq=" + nextEffectSeq
                + " acked=" + dispatchedThrough
                + " index=" + appliedIndex
                + "]";
    }
}
