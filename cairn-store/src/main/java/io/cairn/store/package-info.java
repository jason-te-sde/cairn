/**
 * Durability: the command log, snapshots, and the content-addressed blob store.
 *
 * <p>Three ports and a filesystem implementation of each. The ports exist because the kernel must
 * not know what a disk is, and because the simulator substitutes implementations that model a crash
 * — see {@code io.cairn.store.memory}.
 *
 * <p>{@link io.cairn.store.Recovery} is the twenty lines that reconstruct a registry from a
 * snapshot plus a log, and {@link io.cairn.store.Recovery#checkpoint} is the one place the ordering
 * between writing a snapshot and discarding the log below it is stated.
 */
package io.cairn.store;
