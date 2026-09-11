package io.cairn.store;

import io.cairn.core.Digest;

/**
 * The bytes that arrived do not hash to the digest that was promised.
 *
 * <p>This exception is the reason {@code cairn-store} exists as a separate module rather than as a
 * file-writing helper. The registry this project is derived from accepted a {@code fileHash} field
 * from its client and stored it, having never hashed the file: its integrity guarantee was that
 * nobody had lied to it. Here the digest is computed from the bytes as they stream past, the
 * promised one is compared to it, and a mismatch means nothing is moved into place and nothing is
 * recorded.
 */
public final class DigestMismatchException extends StoreException {

    private final Digest expected;
    private final Digest actual;

    public DigestMismatchException(Digest expected, Digest actual) {
        super("artifact does not match its digest: expected " + expected + ", computed " + actual);
        this.expected = expected;
        this.actual = actual;
    }

    /** What the caller said the artifact would hash to. */
    public Digest expected() {
        return expected;
    }

    /** What it actually hashed to. */
    public Digest actual() {
        return actual;
    }
}
