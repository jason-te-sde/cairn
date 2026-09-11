package io.cairn.core;

/**
 * An effect with its position in the registry's effect log.
 *
 * <p>The sequence number is assigned by the kernel, is contiguous from 1, and is never reused or
 * reordered. That makes it the identity a consumer deduplicates on, which is the only reason
 * at-most-once delivery is achievable without distributed transactions: a consumer that remembers
 * the highest sequence number it has applied can discard anything at or below it, and because
 * delivery is in order, one number is enough state for the whole stream.
 *
 * @param seq position in the effect log, starting at 1
 * @param effect what to do
 */
public record SequencedEffect(long seq, Effect effect) implements Comparable<SequencedEffect> {

    public SequencedEffect {
        if (seq < 1) {
            throw new IllegalArgumentException("effect sequence numbers start at 1, got " + seq);
        }
        if (effect == null) {
            throw new IllegalArgumentException("effect must not be null");
        }
    }

    @Override
    public int compareTo(SequencedEffect other) {
        return Long.compare(seq, other.seq);
    }

    @Override
    public String toString() {
        return "#" + seq + " " + effect;
    }
}
