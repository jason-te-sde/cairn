package io.cairn.codec;

/**
 * The input was not a valid encoding.
 *
 * <p>Always means the bytes are wrong, never that the caller asked for something impossible. That
 * distinction is why this is a checked exception in everything but name: a decode failure is a
 * corrupt file or a hostile peer, and the recovery — truncate the log here, refuse this message —
 * is different from the recovery for a programming error, so the two must not arrive as the same
 * type.
 */
public final class CodecException extends RuntimeException {

    CodecException(String message) {
        super(message);
    }

    CodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
