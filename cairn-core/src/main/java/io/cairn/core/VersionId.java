package io.cairn.core;

/**
 * The version of a model, as written by whoever released it.
 *
 * <p>Case is preserved and no ordering is inferred from the characters: {@code 1.10.0} sorts before
 * {@code 1.9.0} here, which is wrong as a version order and right as a map order. The registry
 * never needs to guess which version is newer, because {@link Stage#PRODUCTION} is a fact somebody
 * asserted rather than a conclusion drawn from a string. {@code docs/design/0005-scope.md} has the
 * argument.
 *
 * @param value the version string
 */
public record VersionId(String value) implements Comparable<VersionId> {

    public VersionId {
        value = Names.versionId(value);
    }

    /** Factory for call sites where {@code new VersionId(...)} reads worse than {@code of(...)}. */
    public static VersionId of(String value) {
        return new VersionId(value);
    }

    @Override
    public int compareTo(VersionId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
