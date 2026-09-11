package io.cairn.core;

/**
 * Every bound the registry enforces, in one place.
 *
 * <p>These are not stylistic. A decoder reading a length from a possibly corrupt file needs an
 * upper bound before it allocates, and a name with no length limit is a denial of service through
 * an API that looks like it is working. Keeping them together means the codec's bounds and the
 * kernel's validation cannot drift apart, and it gives the operations guide one table to quote.
 */
public final class Limits {

    /** Longest model identifier, in characters. */
    public static final int MAX_MODEL_ID = 128;

    /** Longest version identifier, in characters. */
    public static final int MAX_VERSION_ID = 128;

    /** Longest actor string recorded on a mutation. */
    public static final int MAX_ACTOR = 128;

    /** Most labels a single version may carry. */
    public static final int MAX_LABELS = 32;

    /** Longest label key, in characters. */
    public static final int MAX_LABEL_KEY = 64;

    /** Longest label value, in characters. */
    public static final int MAX_LABEL_VALUE = 1024;

    /**
     * Most immediate parents a version may declare.
     *
     * <p>Lineage is a DAG and the transitive ancestry is unbounded; this bounds the fan-out of one
     * node, which is what a single command has to validate.
     */
    public static final int MAX_PARENTS = 16;

    /** Largest artifact the registry will record, in bytes: 256 GiB. */
    public static final long MAX_ARTIFACT_BYTES = 256L * 1024 * 1024 * 1024;

    private Limits() {}
}
