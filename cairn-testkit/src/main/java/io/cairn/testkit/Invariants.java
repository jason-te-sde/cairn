package io.cairn.testkit;

import io.cairn.codec.Codec;
import io.cairn.core.Blob;
import io.cairn.core.Digest;
import io.cairn.core.Model;
import io.cairn.core.ModelVersion;
import io.cairn.core.Ref;
import io.cairn.core.RegistryView;
import io.cairn.core.SequencedEffect;
import io.cairn.core.Stage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every property the registry promises, as an executable check, run after every step.
 *
 * <p>After <b>every step</b>, not at the end of a run. A registry that briefly holds two production
 * versions and then settles looks perfectly healthy by the time a simulation finishes, and an
 * end-state assertion would pass. Several of the properties below are also claims about history
 * rather than about an instant, which is why this class is stateful: it remembers the digest every
 * version has ever had so it can notice one changing, and it remembers the highest applied index
 * per replica so it can notice durability going backwards across a crash.
 *
 * <table>
 *   <caption>The properties</caption>
 *   <tr><th>#</th><th>Name</th><th>Means</th></tr>
 *   <tr><td>I1</td><td>Convergence</td>
 *       <td>replicas fed the same log hold byte-identical state</td></tr>
 *   <tr><td>I2</td><td>Version immutability</td>
 *       <td>a published version's artifact digest never changes</td></tr>
 *   <tr><td>I3</td><td>Reference integrity</td>
 *       <td>every blob's reference count equals the live versions pointing at it</td></tr>
 *   <tr><td>I4</td><td>Collection safety</td>
 *       <td>an artifact is absent only when unreferenced, and never referenced again</td></tr>
 *   <tr><td>I5</td><td>Stage exclusivity</td>
 *       <td>at most one production version per model, and every stage is reachable</td></tr>
 *   <tr><td>I6</td><td>Effect log integrity</td>
 *       <td>the outbox is contiguous from the watermark and the watermark only rises</td></tr>
 *   <tr><td>I7</td><td>Exactly-once delivery</td>
 *       <td>the downstream world saw each acknowledged effect once, in order</td></tr>
 *   <tr><td>I8</td><td>Monotonicity</td>
 *       <td>no replica's applied index goes backwards, including across a crash</td></tr>
 *   <tr><td>I9</td><td>Lineage integrity</td>
 *       <td>every declared ancestor exists, live versions have live parents, no cycles</td></tr>
 *   <tr><td>I10</td><td>Rejection purity</td>
 *       <td>a refused command changes nothing but the log position</td></tr>
 *   <tr><td>I11</td><td>Artifact integrity</td>
 *       <td>the bytes stored for a present artifact hash to its digest</td></tr>
 *   <tr><td>I12</td><td>Downstream agreement</td>
 *       <td>once delivery catches up, the outside world agrees about what is in production</td></tr>
 * </table>
 *
 * <p>I1 through I10 are about replicated state and are checked from a {@link RegistryView}, so they
 * work against any implementation of the rules — which is what makes it possible to point them at
 * implementations with known bugs and confirm they go red. I11 and I12 are about the system around
 * the state: the blob store's bytes and the downstream consumer's beliefs, checked by
 * {@link #artifactIntegrity} and {@link #downstreamAgreement}.
 */
public final class Invariants {

    private final long seed;

    /** Every digest each version has ever been seen with. More than one entry is a violation. */
    private final Map<Ref, Set<Digest>> digestsSeen = new HashMap<>();

    /** Highest applied index seen per replica, for the claim that durability does not regress. */
    private final Map<String, Long> highestApplied = new HashMap<>();

    /** Highest watermark seen per replica. */
    private final Map<String, Long> highestWatermark = new HashMap<>();

    /** Artifacts that have been collected, so a later reference to one is caught. */
    private final Set<Digest> collected = new HashSet<>();

    private long checks;

    public Invariants(long seed) {
        this.seed = seed;
    }

    /** How many times {@link #check} has run, for a suite that wants to prove it did work. */
    public long checkCount() {
        return checks;
    }

    /**
     * Checks every state property against one replica.
     *
     * @param step the step number, for the failure message
     * @param replica a name for the replica, so a failure says which one
     * @param view the state to check
     * @throws InvariantViolation on the first property that fails
     */
    public void check(long step, String replica, RegistryView view) {
        checks++;
        versionImmutability(step, view);
        referenceIntegrity(step, view);
        collectionSafety(step, view);
        stageExclusivity(step, view);
        effectLogIntegrity(step, replica, view);
        monotonicity(step, replica, view);
        lineageIntegrity(step, view);
    }

    /** I1: every replica holds the same state, by canonical digest. */
    public void convergence(long step, Map<String, RegistryView> replicas) {
        checks++;
        String reference = null;
        String referenceDigest = null;
        for (var entry : replicas.entrySet()) {
            String digest = Codec.stateDigestHex(entry.getValue());
            if (reference == null) {
                reference = entry.getKey();
                referenceDigest = digest;
                continue;
            }
            if (!digest.equals(referenceDigest)) {
                throw new InvariantViolation("I1 Convergence", seed, step,
                        "replica " + reference + " holds " + referenceDigest.substring(0, 12)
                                + " but replica " + entry.getKey() + " holds "
                                + digest.substring(0, 12) + "; "
                                + describeDivergence(replicas.get(reference), entry.getValue()));
            }
        }
    }

    /** I2: a published version's artifact never changes. */
    private void versionImmutability(long step, RegistryView view) {
        for (Model model : view.models().values()) {
            for (ModelVersion version : model.versions().values()) {
                Ref ref = new Ref(model.id(), version.version());
                Set<Digest> seen =
                        digestsSeen.computeIfAbsent(ref, key -> new HashSet<>());
                seen.add(version.artifact());
                if (seen.size() > 1) {
                    throw new InvariantViolation("I2 Version immutability", seed, step,
                            ref + " has held more than one artifact: " + seen);
                }
            }
        }
    }

    /** I3: the denormalized reference count agrees with a recount from scratch. */
    private void referenceIntegrity(long step, RegistryView view) {
        Map<Digest, Integer> recounted = new HashMap<>();
        for (Model model : view.models().values()) {
            for (ModelVersion version : model.versions().values()) {
                if (!version.live()) {
                    continue;
                }
                Blob blob = view.blobs().get(version.artifact());
                if (blob == null) {
                    throw new InvariantViolation("I3 Reference integrity", seed, step,
                            new Ref(model.id(), version.version()) + " references artifact "
                                    + version.artifact().shortHex()
                                    + ", which the registry has no record of");
                }
                recounted.merge(version.artifact(), 1, Integer::sum);
            }
        }
        for (Blob blob : view.blobs().values()) {
            int expected = recounted.getOrDefault(blob.digest(), 0);
            if (blob.refCount() != expected) {
                throw new InvariantViolation("I3 Reference integrity", seed, step,
                        "artifact " + blob.digest().shortHex() + " says refCount="
                                + blob.refCount() + " but " + expected
                                + " live version(s) reference it");
            }
        }
    }

    /** I4: absent means unreferenced, and a collected artifact is never referenced again. */
    private void collectionSafety(long step, RegistryView view) {
        for (Blob blob : view.blobs().values()) {
            if (!blob.present()) {
                collected.add(blob.digest());
                if (blob.refCount() > 0) {
                    throw new InvariantViolation("I4 Collection safety", seed, step,
                            "artifact " + blob.digest().shortHex() + " is absent but "
                                    + blob.refCount() + " version(s) still reference it");
                }
            } else if (collected.contains(blob.digest()) && blob.refCount() > 0) {
                // Coming back is legal: the bytes can be re-ingested. What would not be legal is a
                // version referencing it during the window when it was gone, which the check above
                // covers, or it coming back without being re-ingested, which nothing can do.
                collected.remove(blob.digest());
            }
        }

        // The end of the same argument, and the part a simulation had to find. An effect sitting
        // in the outbox is a decision that has not reached the world yet, so an artifact with an
        // undelivered collection order is on its way to being deleted whatever the registry
        // currently says about it. No live version may point at one.
        for (Model model : view.models().values()) {
            for (ModelVersion version : model.versions().values()) {
                if (!version.live()) {
                    continue;
                }
                Blob blob = view.blobs().get(version.artifact());
                if (blob != null
                        && blob.collectSeq() > 0
                        && view.dispatchedThrough() < blob.collectSeq()) {
                    throw new InvariantViolation("I4 Collection safety", seed, step,
                            "live version " + new Ref(model.id(), version.version())
                                    + " references artifact " + blob.digest().shortHex()
                                    + ", which has an undelivered collection order at #"
                                    + blob.collectSeq() + " (watermark "
                                    + view.dispatchedThrough() + ")");
                }
            }
        }
    }

    /** I5: at most one production version per model, and no version in an impossible stage. */
    private void stageExclusivity(long step, RegistryView view) {
        for (Model model : view.models().values()) {
            List<Ref> inProduction = new ArrayList<>(1);
            for (ModelVersion version : model.versions().values()) {
                if (version.live() && version.stage() == Stage.PRODUCTION) {
                    inProduction.add(new Ref(model.id(), version.version()));
                }
                if (!version.live() && version.stage() == Stage.PRODUCTION) {
                    throw new InvariantViolation("I5 Stage exclusivity", seed, step,
                            new Ref(model.id(), version.version())
                                    + " is a tombstone in PRODUCTION, so serving would load a"
                                    + " deleted version");
                }
            }
            if (inProduction.size() > 1) {
                throw new InvariantViolation("I5 Stage exclusivity", seed, step,
                        model.id() + " has " + inProduction.size()
                                + " production versions: " + inProduction);
            }
        }
    }

    /** I6: the outbox is contiguous from the watermark, and the watermark only rises. */
    private void effectLogIntegrity(long step, String replica, RegistryView view) {
        long expected = view.dispatchedThrough() + 1;
        for (SequencedEffect effect : view.outbox()) {
            if (effect.seq() != expected) {
                throw new InvariantViolation("I6 Effect log integrity", seed, step,
                        "replica " + replica + " outbox expected #" + expected + ", found #"
                                + effect.seq());
            }
            expected++;
        }
        if (expected != view.nextEffectSeq()) {
            throw new InvariantViolation("I6 Effect log integrity", seed, step,
                    "replica " + replica + " outbox ends at #" + (expected - 1)
                            + " but the next sequence number is #" + view.nextEffectSeq());
        }
        Long previous = highestWatermark.put(replica, view.dispatchedThrough());
        if (previous != null && view.dispatchedThrough() < previous) {
            throw new InvariantViolation("I6 Effect log integrity", seed, step,
                    "replica " + replica + " watermark went backwards: " + previous + " -> "
                            + view.dispatchedThrough());
        }
    }

    /** I8: an applied index never goes backwards, including across a crash. */
    private void monotonicity(long step, String replica, RegistryView view) {
        Long previous = highestApplied.get(replica);
        if (previous != null && view.appliedIndex() < previous) {
            throw new InvariantViolation("I8 Monotonicity", seed, step,
                    "replica " + replica + " applied index went backwards: " + previous + " -> "
                            + view.appliedIndex() + "; a command that was acknowledged has been"
                            + " forgotten");
        }
        highestApplied.put(replica, Math.max(previous == null ? 0 : previous, view.appliedIndex()));
    }

    /** I9: declared ancestors exist, live versions have live parents, and there are no cycles. */
    private void lineageIntegrity(long step, RegistryView view) {
        for (Model model : view.models().values()) {
            for (ModelVersion version : model.versions().values()) {
                Ref self = new Ref(model.id(), version.version());
                for (Ref parent : version.parents()) {
                    if (parent.equals(self)) {
                        throw new InvariantViolation("I9 Lineage integrity", seed, step,
                                self + " is its own parent");
                    }
                    ModelVersion ancestor = view.version(parent).orElse(null);
                    if (ancestor == null) {
                        throw new InvariantViolation("I9 Lineage integrity", seed, step,
                                self + " declares parent " + parent + ", which does not exist");
                    }
                    if (version.live() && !ancestor.live()) {
                        throw new InvariantViolation("I9 Lineage integrity", seed, step,
                                "live version " + self + " has a deleted parent " + parent
                                        + ", so its provenance dangles");
                    }
                }
                assertNoCycleFrom(step, view, self);
            }
        }
    }

    /**
     * Walks the ancestry looking for a cycle.
     *
     * <p>A cycle should be structurally impossible, because a parent has to exist before the child
     * that names it and nothing can be added to an existing version. Checked anyway: the whole
     * point of an invariant is to hold a structural argument still while the code around it
     * changes, and "it cannot happen" is the reasoning that stops being true first.
     */
    private void assertNoCycleFrom(long step, RegistryView view, Ref start) {
        Set<Ref> visited = new HashSet<>();
        List<Ref> frontier = new ArrayList<>(List.of(start));
        while (!frontier.isEmpty()) {
            Ref current = frontier.remove(frontier.size() - 1);
            ModelVersion version = view.version(current).orElse(null);
            if (version == null) {
                continue;
            }
            for (Ref parent : version.parents()) {
                if (parent.equals(start)) {
                    throw new InvariantViolation("I9 Lineage integrity", seed, step,
                            "lineage cycle through " + start);
                }
                if (visited.add(parent)) {
                    frontier.add(parent);
                }
            }
        }
    }

    /** I10: a refused command changed nothing but the log position. */
    public void rejectionPurity(long step, String replica, RegistryView before, RegistryView after) {
        checks++;
        if (!RegistryView.sameState(before, after)) {
            throw new InvariantViolation("I10 Rejection purity", seed, step,
                    "replica " + replica + " changed state on a rejected command: "
                            + describeDivergence(before, after));
        }
        if (after.appliedIndex() != before.appliedIndex() + 1) {
            throw new InvariantViolation("I10 Rejection purity", seed, step,
                    "replica " + replica + " did not advance its log position on a rejection: "
                            + before.appliedIndex() + " -> " + after.appliedIndex());
        }
    }

    /**
     * I7: the downstream world saw each acknowledged effect exactly once, in order.
     *
     * @param observed everything the downstream sink actually applied, in arrival order
     * @param acknowledged the registry's watermark
     */
    public void exactlyOnceDelivery(
            long step, List<SequencedEffect> observed, long acknowledged) {
        checks++;
        Set<Long> seen = new HashSet<>();
        long previous = 0;
        for (SequencedEffect effect : observed) {
            if (!seen.add(effect.seq())) {
                throw new InvariantViolation("I7 Exactly-once delivery", seed, step,
                        "effect #" + effect.seq() + " was applied more than once");
            }
            if (effect.seq() <= previous) {
                throw new InvariantViolation("I7 Exactly-once delivery", seed, step,
                        "effect #" + effect.seq() + " was applied after #" + previous
                                + "; delivery must follow the sequence");
            }
            previous = effect.seq();
        }
        // The acknowledged prefix is exactly what must have been applied. Anything acknowledged and
        // not applied is a lost effect; the reverse — applied but not yet acknowledged — is normal
        // and is the crash window the design tolerates.
        for (long seq = 1; seq <= acknowledged; seq++) {
            if (!seen.contains(seq)) {
                throw new InvariantViolation("I7 Exactly-once delivery", seed, step,
                        "effect #" + seq + " is acknowledged through " + acknowledged
                                + " but was never applied downstream");
            }
        }
    }

    /**
     * I12: once delivery has caught up, the downstream world agrees with the registry about which
     * version of each model is in production.
     *
     * <p>The property an operator actually cares about, and the only one here that can catch an
     * ordering mistake in the effect stream. A registry can be internally flawless and still have
     * left a serving tier loading a version it retired, if the effects describing the change
     * arrived out of order or one of them was dropped. Checked only when the outbox is empty:
     * while delivery is behind, disagreement is the design working.
     *
     * @param expected the registry's production version per model
     * @param actual what the downstream consumer believes
     */
    public void downstreamAgreement(
            long step,
            Map<io.cairn.core.ModelId, io.cairn.core.VersionId> expected,
            Map<io.cairn.core.ModelId, io.cairn.core.VersionId> actual) {
        checks++;
        if (!expected.equals(actual)) {
            throw new InvariantViolation("I12 Downstream agreement", seed, step,
                    "the registry says production is " + new java.util.TreeMap<>(expected)
                            + " but the downstream world believes "
                            + new java.util.TreeMap<>(actual));
        }
    }

    /**
     * I11: the bytes stored for every present artifact hash to its digest.
     *
     * @param view the registry
     * @param digestOfStoredBytes computes the digest of what the blob store actually holds, or null
     *     if it holds nothing under that digest
     */
    public void artifactIntegrity(
            long step, RegistryView view, java.util.function.Function<Digest, Digest> digestOfStoredBytes) {
        checks++;
        for (Blob blob : view.blobs().values()) {
            if (!blob.present()) {
                continue;
            }
            Digest actual = digestOfStoredBytes.apply(blob.digest());
            if (actual == null) {
                throw new InvariantViolation("I11 Artifact integrity", seed, step,
                        "the registry says artifact " + blob.digest().shortHex()
                                + " is present but the blob store has nothing under that digest");
            }
            if (!actual.equals(blob.digest())) {
                throw new InvariantViolation("I11 Artifact integrity", seed, step,
                        "artifact stored as " + blob.digest().shortHex() + " actually hashes to "
                                + actual.shortHex() + "; the digest is a lie");
            }
        }
    }

    /** A short description of where two states differ, for a failure message worth reading. */
    private static String describeDivergence(RegistryView left, RegistryView right) {
        if (!left.models().equals(right.models())) {
            for (var id : left.models().keySet()) {
                Model mine = left.models().get(id);
                Model theirs = right.models().get(id);
                if (!mine.equals(theirs)) {
                    return "model " + id + " differs: " + mine.versions().keySet() + " vs "
                            + (theirs == null ? "absent" : theirs.versions().keySet().toString());
                }
            }
            return "models differ: " + left.models().keySet() + " vs " + right.models().keySet();
        }
        if (!left.blobs().equals(right.blobs())) {
            return "blobs differ: " + left.blobs().values() + " vs " + right.blobs().values();
        }
        if (!left.outbox().equals(right.outbox())) {
            return "outbox differs: " + left.outbox() + " vs " + right.outbox();
        }
        if (left.dispatchedThrough() != right.dispatchedThrough()) {
            return "watermark differs: " + left.dispatchedThrough() + " vs "
                    + right.dispatchedThrough();
        }
        if (left.nextEffectSeq() != right.nextEffectSeq()) {
            return "next effect sequence differs: " + left.nextEffectSeq() + " vs "
                    + right.nextEffectSeq();
        }
        return "the states differ in a way this description does not cover";
    }
}
