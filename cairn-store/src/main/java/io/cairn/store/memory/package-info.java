/**
 * In-memory implementations of the durability ports.
 *
 * <p>Shipped as main code rather than test code because a module that defines a port owes its
 * embedders an implementation they can write tests against, and because the simulator in
 * {@code cairn-testkit} is built on these. {@link io.cairn.store.memory.InMemoryCommandLog} models
 * the sync boundary, which is what makes an injected crash mean anything.
 */
package io.cairn.store.memory;
