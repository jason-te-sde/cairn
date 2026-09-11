package io.cairn.core;

/**
 * The name of a model: lowercase, bounded, and free of anything a delimiter-based format would
 * choke on.
 *
 * <p>Validated in the constructor, so an invalid identifier cannot exist. That is the reason the
 * kernel's rejection codes are all about state — an unknown parent, an illegal transition — and
 * never about syntax: by the time a command reaches the kernel, a malformed name has already been
 * refused at the edge with a 400.
 *
 * @param value the identifier
 */
public record ModelId(String value) implements Comparable<ModelId> {

    public ModelId {
        value = Names.modelId(value);
    }

    /** Factory for call sites where {@code new ModelId(...)} reads worse than {@code of(...)}. */
    public static ModelId of(String value) {
        return new ModelId(value);
    }

    @Override
    public int compareTo(ModelId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
