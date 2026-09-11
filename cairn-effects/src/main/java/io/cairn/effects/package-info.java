/**
 * Delivery: getting each committed effect to the outside world exactly once.
 *
 * <p>Split into the two halves that make that claim honest.
 * {@link io.cairn.effects.Dispatcher} supplies <b>at least once</b>: it reads the registry's
 * outbox in order, delivers, and moves the watermark by proposing a command through the log, so a
 * crash anywhere in that sequence results in redelivery rather than loss.
 * {@link io.cairn.effects.IdempotentSink} supplies <b>at most once</b>: it discards an offer at or
 * below what a consumer's {@link io.cairn.effects.AppliedLedger} already records.
 *
 * <p>Neither half is sufficient and neither requires a distributed transaction, which is the point.
 * {@code docs/design/0003-effects-are-values.md} has the argument and the failure it replaces.
 */
package io.cairn.effects;
