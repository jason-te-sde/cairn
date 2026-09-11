package io.cairn.core;

import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One published version of one model.
 *
 * <p>{@code artifact} is the field that makes this type worth reading. Once a version exists, its
 * digest never changes — not by a second publish, not by an update, not by anything. There is no
 * command that rewrites it, and invariant I2 asserts across a whole simulation that none appeared.
 * Everything else about a version can move: its stage changes, and it can be tombstoned. What
 * "version 2.1.0 of the fraud model" means, in bytes, cannot.
 *
 * <p>{@code deleted} is a tombstone rather than a removal for the same reason. Deleting the entry
 * would let a later publish reuse the version string with different content, which would make the
 * immutability claim true only of versions nobody had deleted first — a guarantee with a loophole
 * that size is not a guarantee.
 *
 * @param version the version identifier
 * @param artifact the content address of the bytes, fixed for the life of the version
 * @param stage where the version stands
 * @param parents what this version was derived from, sorted and deduplicated
 * @param labels free-form metadata, sorted by key
 * @param publishedAt wall-clock milliseconds, supplied by the caller rather than read from a clock
 * @param publishedBy who published it
 * @param deleted whether this version has been tombstoned
 */
public record ModelVersion(
        VersionId version,
        Digest artifact,
        Stage stage,
        List<Ref> parents,
        SortedMap<String, String> labels,
        long publishedAt,
        String publishedBy,
        boolean deleted) {

    public ModelVersion {
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        if (artifact == null) {
            throw new IllegalArgumentException("artifact must not be null");
        }
        if (stage == null) {
            throw new IllegalArgumentException("stage must not be null");
        }
        if (publishedBy == null) {
            throw new IllegalArgumentException("publishedBy must not be null");
        }
        Names.actor(publishedBy);
        parents = normalizeParents(parents);
        labels = normalizeLabels(labels);
    }

    /**
     * Sorts and deduplicates the parent list.
     *
     * <p>Sorting is not tidiness. Declared order carries no meaning — "derived from A and B" is the
     * same claim as "derived from B and A" — so if the order were preserved, two publishes of the
     * same version with the parents listed differently would compare unequal, and the idempotency
     * check on republish would reject a retry of a request it had already accepted. Normalizing
     * here makes that impossible to get wrong at any call site.
     */
    static List<Ref> normalizeParents(List<Ref> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > Limits.MAX_PARENTS) {
            throw new IllegalArgumentException(
                    "a version may declare at most " + Limits.MAX_PARENTS + " parents, got "
                            + raw.size());
        }
        List<Ref> sorted = new java.util.ArrayList<>(raw.size());
        for (Ref parent : raw) {
            if (parent == null) {
                throw new IllegalArgumentException("parent must not be null");
            }
            if (!sorted.contains(parent)) {
                sorted.add(parent);
            }
        }
        Collections.sort(sorted);
        return List.copyOf(sorted);
    }

    static SortedMap<String, String> normalizeLabels(SortedMap<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySortedMap();
        }
        if (raw.size() > Limits.MAX_LABELS) {
            throw new IllegalArgumentException(
                    "a version may carry at most " + Limits.MAX_LABELS + " labels, got " + raw.size());
        }
        TreeMap<String, String> copy = new TreeMap<>();
        for (var entry : raw.entrySet()) {
            copy.put(Names.labelKey(entry.getKey()), Names.labelValue(entry.getValue()));
        }
        return Collections.unmodifiableSortedMap(copy);
    }

    /** Builds a labels map from alternating key and value arguments, for tests and the CLI. */
    public static SortedMap<String, String> labels(String... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "labels take alternating keys and values, got " + keysAndValues.length
                            + " arguments");
        }
        TreeMap<String, String> map = new TreeMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    ModelVersion withStage(Stage newStage) {
        return new ModelVersion(
                version, artifact, newStage, parents, labels, publishedAt, publishedBy, deleted);
    }

    ModelVersion tombstoned() {
        return new ModelVersion(
                version, artifact, stage, parents, labels, publishedAt, publishedBy, true);
    }

    /** Whether this version still counts: not a tombstone. */
    public boolean live() {
        return !deleted;
    }
}
