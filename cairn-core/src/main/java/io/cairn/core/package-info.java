/**
 * The registry kernel: the rules, and the values they operate on.
 *
 * <p>Nothing in this package performs I/O, reads a clock, starts a thread, or takes a lock, and
 * nothing in it has a dependency outside the JDK. Everything that varies between two runs of the
 * same command sequence — a timestamp, an actor, a log index — arrives as an argument. That is what
 * makes a whole run of the system a function of one integer seed, and it is enforced by the module
 * boundary rather than by discipline: there is nothing on this module's compile path to call.
 *
 * <p>Start at {@link io.cairn.core.Kernel}, which is the one function that matters, then
 * {@link io.cairn.core.Effect} for why side effects are values here rather than statements.
 */
package io.cairn.core;
