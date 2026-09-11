/**
 * The canonical encoding for commands, effects and state.
 *
 * <p>One state has exactly one valid byte string, which is what makes a SHA-256 over the encoding
 * an identity for a registry. The decoder is strict in both directions: it refuses input a
 * conforming encoder could not have produced, including an unsorted map, a boolean outside
 * {@code {0, 1}}, and trailing bytes.
 *
 * <p>{@link io.cairn.codec.Codec} is the whole public surface.
 */
package io.cairn.codec;
