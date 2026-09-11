package io.cairn.core;

import java.util.Collections;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Every version of one model, keyed by version identifier.
 *
 * <p>There is no {@code production} field. The production version is found by looking for the one
 * whose stage says so, which costs a walk of the version map on a path that serving infrastructure
 * calls often — and is still the right trade. A cached pointer would be a second place the answer
 * lives, the invariant that catches the two disagreeing is the same invariant that would be
 * unnecessary without it, and "the denormalized copy drifted" is the most common way a registry
 * ends up serving a model nobody promoted. If the walk ever shows up in a profile, the fix is a
 * cache in the query layer, not a field in the replicated state.
 *
 * @param id the model's name
 * @param versions every version ever published, including tombstones, sorted by version identifier
 */
public record Model(ModelId id, SortedMap<VersionId, ModelVersion> versions) {

    public Model {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        versions = versions == null
                ? Collections.emptySortedMap()
                : Collections.unmodifiableSortedMap(naturalOrder(versions));
    }

    /**
     * Copies into a map that is sorted by the keys' own ordering.
     *
     * <p>{@code new TreeMap<>(sortedMap)} would inherit the source map's comparator, so a caller
     * holding a {@code TreeMap} with {@code Comparator.reverseOrder()} — legal Java, and a
     * perfectly good {@code SortedMap} — would get a descending copy back. Every canonical
     * encoding in this project assumes ascending key order, so the defensive copy has to be
     * defensive about the ordering and not only about the mutability. Caught by a codec test that
     * passed a reversed map in on purpose.
     */
    private static TreeMap<VersionId, ModelVersion> naturalOrder(
            SortedMap<VersionId, ModelVersion> source) {
        TreeMap<VersionId, ModelVersion> copy = new TreeMap<>();
        copy.putAll(source);
        return copy;
    }

    /** A model with no versions. Not a state the kernel ever stores: a model exists by having one. */
    public static Model empty(ModelId id) {
        return new Model(id, Collections.emptySortedMap());
    }

    /** The version in {@link Stage#PRODUCTION}, if any. At most one, by invariant I5. */
    public Optional<ModelVersion> production() {
        for (ModelVersion candidate : versions.values()) {
            if (candidate.live() && candidate.stage() == Stage.PRODUCTION) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** A single version, whether live or tombstoned. */
    public Optional<ModelVersion> version(VersionId version) {
        return Optional.ofNullable(versions.get(version));
    }

    /** A single version, only if it is not a tombstone. */
    public Optional<ModelVersion> liveVersion(VersionId version) {
        return version(version).filter(ModelVersion::live);
    }

    Model with(ModelVersion replacement) {
        TreeMap<VersionId, ModelVersion> next = new TreeMap<>(versions);
        next.put(replacement.version(), replacement);
        return new Model(id, next);
    }

    /** How many versions are not tombstones. */
    public int liveCount() {
        int count = 0;
        for (ModelVersion candidate : versions.values()) {
            if (candidate.live()) {
                count++;
            }
        }
        return count;
    }
}
