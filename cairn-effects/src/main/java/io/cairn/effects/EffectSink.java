package io.cairn.effects;

import io.cairn.core.SequencedEffect;

/**
 * Somewhere for an effect to go: a message broker, a cache invalidator, a blob collector.
 *
 * <p>The contract is deliberately weak, because a strong one would be a lie. A sink is told about
 * an effect <b>at least once</b> and must therefore be idempotent, or be wrapped in
 * {@link IdempotentSink}, which makes it so using the effect's sequence number. That asymmetry —
 * the dispatcher promises at-least-once, the consumer supplies at-most-once — is the whole of the
 * exactly-once argument, and it is the only version of exactly-once that does not require a
 * distributed transaction between the registry and the broker.
 *
 * <p>Throwing is a normal outcome and means "not delivered". The dispatcher will not acknowledge
 * the effect, so it stays in the outbox and is offered again. What a sink must never do is throw
 * <i>after</i> the delivery has taken effect downstream, because that turns a retry into a
 * duplicate — which is exactly what the sequence number is for.
 */
@FunctionalInterface
public interface EffectSink {

    /**
     * Delivers one effect.
     *
     * @throws RuntimeException if it was not delivered; the dispatcher will retry
     */
    void deliver(SequencedEffect effect);

    /** A name for logs and metrics. */
    default String name() {
        return getClass().getSimpleName();
    }
}
