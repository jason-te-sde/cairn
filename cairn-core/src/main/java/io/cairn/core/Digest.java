package io.cairn.core;

/**
 * The content address of an artifact: {@code sha256:} followed by 64 lowercase hex characters.
 *
 * <p>A digest is the only name an artifact has. There is no bucket and no object key in the
 * registry's state, which is deliberate: the system this one is derived from stored
 * {@code s3Bucket} and {@code s3Key} alongside a {@code fileHash} that nothing ever checked, so
 * three fields could disagree and the one that decided what bytes you got was the one nobody
 * verified. Here the digest is the key, the blob store derives the path from it, and a mismatch is
 * structurally impossible because {@code cairn-store} computes the digest while it writes.
 *
 * <p>Only SHA-256 is accepted. Carrying an algorithm prefix anyway costs four characters and means
 * adding a second one later does not require a migration of every stored digest.
 *
 * @param algorithm the hash algorithm; only {@code sha256} is currently valid
 * @param hex the lowercase hexadecimal digest
 */
public record Digest(String algorithm, String hex) implements Comparable<Digest> {

    /** The only algorithm this version accepts. */
    public static final String SHA256 = "sha256";

    /** Length of a SHA-256 digest in hex characters. */
    public static final int SHA256_HEX_LENGTH = 64;

    public Digest {
        if (!SHA256.equals(algorithm)) {
            throw new IllegalArgumentException("unsupported digest algorithm: " + algorithm);
        }
        if (hex == null || hex.length() != SHA256_HEX_LENGTH) {
            throw new IllegalArgumentException(
                    "sha256 digest must be " + SHA256_HEX_LENGTH + " hex characters, got "
                            + (hex == null ? "null" : String.valueOf(hex.length())));
        }
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            boolean lowerHex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!lowerHex) {
                // Uppercase is rejected rather than folded. Two spellings of one digest would be
                // two keys in the blob map and two paths on disk, which is the same class of bug
                // as case-sensitive model names.
                throw new IllegalArgumentException(
                        "sha256 digest must be lowercase hex, found '" + c + "' at " + i);
            }
        }
    }

    /** Parses {@code sha256:<hex>}. */
    public static Digest parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("digest must not be null");
        }
        int colon = text.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("digest must be algorithm:hex, got '" + text + "'");
        }
        return new Digest(text.substring(0, colon), text.substring(colon + 1));
    }

    /** Wraps raw hash bytes, which is what a {@code MessageDigest} hands back. */
    public static Digest ofSha256(byte[] hash) {
        if (hash == null || hash.length != 32) {
            throw new IllegalArgumentException(
                    "sha256 hash must be 32 bytes, got " + (hash == null ? "null" : hash.length));
        }
        StringBuilder hex = new StringBuilder(SHA256_HEX_LENGTH);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return new Digest(SHA256, hex.toString());
    }

    /** The first twelve hex characters, for a log line or a terminal column. */
    public String shortHex() {
        return hex.substring(0, 12);
    }

    @Override
    public int compareTo(Digest other) {
        int byAlgorithm = algorithm.compareTo(other.algorithm);
        return byAlgorithm != 0 ? byAlgorithm : hex.compareTo(other.hex);
    }

    @Override
    public String toString() {
        return algorithm + ":" + hex;
    }
}
