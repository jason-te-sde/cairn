package io.cairn.effects;

import io.cairn.core.SequencedEffect;
import java.util.ArrayList;
import java.util.List;

/**
 * A sink that remembers what it was given, and can be told to start refusing.
 *
 * <p>Main code rather than test code, because it is how an embedder writes a test for their own
 * consumer, and because the simulator uses it as the downstream world. {@link #failFrom} is the
 * fault injection: a sink that has never refused anything is a dispatcher whose retry path has
 * never run.
 */
public final class RecordingSink implements EffectSink {

    private final List<SequencedEffect> received = new ArrayList<>();
    private long failFrom = Long.MAX_VALUE;

    @Override
    public void deliver(SequencedEffect effect) {
        if (effect.seq() >= failFrom) {
            throw new IllegalStateException("injected sink failure at #" + effect.seq());
        }
        received.add(effect);
    }

    /** Everything delivered, in the order it arrived, duplicates included. */
    public List<SequencedEffect> received() {
        return List.copyOf(received);
    }

    /** Start refusing every effect at or above {@code seq}. */
    public void failFrom(long seq) {
        this.failFrom = seq;
    }

    /** Stop refusing. */
    public void recover() {
        this.failFrom = Long.MAX_VALUE;
    }

    @Override
    public String name() {
        return "recording";
    }
}
