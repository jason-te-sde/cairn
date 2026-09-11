/**
 * The registry as a running process: an HTTP API, Prometheus metrics, and a command-line client.
 *
 * <p>{@link io.cairn.server.Engine} is the part worth reading. One thread owns the kernel, each
 * command publishes a new immutable state through a volatile field, and reads never touch that
 * thread — so a reader holds a whole consistent registry with no lock and no possibility of seeing
 * a half-applied command.
 *
 * <p>No web framework and no JSON library. {@code docs/design/0006-no-framework.md} has the
 * argument; the short version is that the API has fifteen fields and a strict parser written for it
 * is less risk than a permissive one written for everybody.
 */
package io.cairn.server;
