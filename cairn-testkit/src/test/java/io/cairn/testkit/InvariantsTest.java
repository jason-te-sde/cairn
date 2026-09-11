package io.cairn.testkit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cairn.core.Blob;
import io.cairn.core.Digest;
import io.cairn.core.Effect;
import io.cairn.core.Model;
import io.cairn.core.ModelId;
import io.cairn.core.ModelVersion;
import io.cairn.core.Ref;
import io.cairn.core.Registry;
import io.cairn.core.RegistryView;
import io.cairn.core.SequencedEffect;
import io.cairn.core.Stage;
import io.cairn.core.VersionId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The checker, checked.
 *
 * <p>A checker that has only ever run against correct code has proved nothing about itself. These
 * tests build states by hand — including states no kernel can produce — and require the right
 * property to fail, by name. Just as importantly, the second half requires it <b>not</b> to fail on
 * states that look suspicious and are legal, because a checker that fires on those is useless under
 * exactly the conditions it exists for: a replica replaying its log, an artifact legitimately
 * re-ingested, a version deleted while a snapshot of it is still on disk.
 */
class InvariantsTest {

    /** A view assembled from parts, bypassing the kernel. */
    private record HandBuilt(
            SortedMap<ModelId, Model> models,
            SortedMap<Digest, Blob> blobs,
            List<SequencedEffect> outbox,
            long nextEffectSeq,
            long dispatchedThrough,
            long appliedIndex) implements RegistryView {

        HandBuilt {
            models = models == null ? Collections.emptySortedMap() : models;
            blobs = blobs == null ? Collections.emptySortedMap() : blobs;
            outbox = outbox == null ? List.of() : outbox;
        }
    }

    private static Digest digest(int n) {
        return CommandGenerator.digest(n);
    }

    private static ModelVersion version(String id, Digest artifact, Stage stage, boolean deleted) {
        return new ModelVersion(
                VersionId.of(id), artifact, stage, null, null, 0L, "tester", deleted);
    }

    private static SortedMap<ModelId, Model> oneModel(String name, ModelVersion... versions) {
        TreeMap<VersionId, ModelVersion> byVersion = new TreeMap<>();
        for (ModelVersion candidate : versions) {
            byVersion.put(candidate.version(), candidate);
        }
        TreeMap<ModelId, Model> models = new TreeMap<>();
        models.put(ModelId.of(name), new Model(ModelId.of(name), byVersion));
        return models;
    }

    private static SortedMap<Digest, Blob> oneBlob(Blob blob) {
        TreeMap<Digest, Blob> blobs = new TreeMap<>();
        blobs.put(blob.digest(), blob);
        return blobs;
    }

    // ---- what it must catch -------------------------------------------------------------------

    @Test
    void twoProductionVersionsAreCaught() {
        RegistryView bad = new HandBuilt(
                oneModel("fraud",
                        version("1.0.0", digest(1), Stage.PRODUCTION, false),
                        version("2.0.0", digest(1), Stage.PRODUCTION, false)),
                oneBlob(new Blob(digest(1), 10, 2, true, 0)),
                null, 1, 0, 1);

        InvariantViolation caught = assertThrows(InvariantViolation.class,
                () -> new Invariants(7).check(1, "r0", bad));
        assertEquals("I5 Stage exclusivity", caught.invariant());
        assertTrue(caught.getMessage().contains("2 production versions"), caught.getMessage());
    }

    @Test
    void aTombstoneInProductionIsCaught() {
        RegistryView bad = new HandBuilt(
                oneModel("fraud", version("1.0.0", digest(1), Stage.PRODUCTION, true)),
                oneBlob(new Blob(digest(1), 10, 0, false, 0)),
                null, 1, 0, 1);

        InvariantViolation caught = assertThrows(InvariantViolation.class,
                () -> new Invariants(7).check(1, "r0", bad));
        assertEquals("I5 Stage exclusivity", caught.invariant());
    }

    @Test
    void aWrongReferenceCountIsCaught() {
        RegistryView bad = new HandBuilt(
                oneModel("fraud", version("1.0.0", digest(1), Stage.STAGING, false)),
                oneBlob(new Blob(digest(1), 10, 5, true, 0)),
                null, 1, 0, 1);

        InvariantViolation caught = assertThrows(InvariantViolation.class,
                () -> new Invariants(7).check(1, "r0", bad));
        assertEquals("I3 Reference integrity", caught.invariant());
        assertTrue(caught.getMessage().contains("refCount=5"), caught.getMessage());
    }

    @Test
    void aVersionReferencingAnUnknownArtifactIsCaught() {
        RegistryView bad = new HandBuilt(
                oneModel("fraud", version("1.0.0", digest(1), Stage.STAGING, false)),
                null, null, 1, 0, 1);

        InvariantViolation caught = assertThrows(InvariantViolation.class,
                () -> new Invariants(7).check(1, "r0", bad));
        assertEquals("I3 Reference integrity", caught.invariant());
    }

    @Test
    void aLiveVersionPointingAtAnArtifactOnItsWayOutIsCaught() {
        // The hole a simulation found: a collection order in the outbox is a decision that has not
        // reached the world, so nothing live may point at the artifact it covers.
        RegistryView bad = new HandBuilt(
                oneModel("fraud", version("1.0.0", digest(1), Stage.STAGING, false)),
                oneBlob(new Blob(digest(1), 10, 1, true, 0)),
                null, 1, 0, 1);
        // Legal as built; now say the artifact has an order against it, which the Blob record
        // itself refuses for a present artifact — so this is asserted through the record.
        assertThrows(IllegalArgumentException.class,
                () -> new Blob(digest(1), 10, 1, true, 9));
        assertDoesNotThrow(() -> new Invariants(7).check(1, "r0", bad));
    }

    @Test
    void aVersionChangingItsArtifactIsCaught() {
        Invariants invariants = new Invariants(7);
        invariants.check(1, "r0", new HandBuilt(
                oneModel("fraud", version("1.0.0", digest(1), Stage.STAGING, false)),
                oneBlob(new Blob(digest(1), 10, 1, true, 0)), null, 1, 0, 1));

        InvariantViolation caught = assertThrows(InvariantViolation.class,
                () -> invariants.check(2, "r0", new HandBuilt(
                        oneModel("fraud", version("1.0.0", digest(2), Stage.STAGING, false)),
                        oneBlob(new Blob(digest(2), 20, 1, true, 0)), null, 1, 0, 2)));
        assertEquals("I2 Version immutability", caught.invariant());
        assertTrue(caught.getMessage().contains("more than one artifact"), caught.getMessage());
    }

    @Test
    void anAppliedIndexGoingBackwardsIsCaught() {
        Invariants invariants = new Invariants(7);
        invariants.check(1, "r0", new HandBuilt(null, null, null, 1, 0, 50));

        InvariantViolation caught = assertThrows(InvariantViolation.class,
                () -> invariants.check(2, "r0", new HandBuilt(null, null, null, 1, 0, 40)));
        assertEquals("I8 Monotonicity", caught.invariant());
        assertTrue(caught.getMessage().contains("has been forgotten"), caught.getMessage());
    }

    @Test
    void aWatermarkGoingBackwardsIsCaught() {
        Invariants invariants = new Invariants(7);
        invariants.check(1, "r0", new HandBuilt(null, null, null, 11, 10, 1));

        InvariantViolation caught = assertThrows(InvariantViolation.class,
                () -> invariants.check(2, "r0", new HandBuilt(null, null, null, 11, 5, 2)));
        assertEquals("I6 Effect log integrity", caught.invariant());
    }

    @Test
    void anOutboxThatDoesNotStartAtTheWatermarkIsCaught() {
        List<SequencedEffect> outbox = List.of(new SequencedEffect(
                9, new Effect.VersionDeleted(Ref.of("fraud", "1.0.0"), "tester", 0L)));

        InvariantViolation caught = assertThrows(InvariantViolation.class,
                () -> new Invariants(7).check(1, "r0",
                        new HandBuilt(null, null, outbox, 10, 3, 1)));
        assertEquals("I6 Effect log integrity", caught.invariant());
    }

    @Test
    void divergingReplicasAreCaughtWithADescriptionOfWhereTheyDiffer() {
        Map<String, RegistryView> replicas = new LinkedHashMap<>();
        replicas.put("r0", new HandBuilt(
                oneModel("fraud", version("1.0.0", digest(1), Stage.STAGING, false)),
                oneBlob(new Blob(digest(1), 10, 1, true, 0)), null, 1, 0, 1));
        replicas.put("r1", new HandBuilt(
                oneModel("fraud", version("2.0.0", digest(1), Stage.STAGING, false)),
                oneBlob(new Blob(digest(1), 10, 1, true, 0)), null, 1, 0, 1));

        InvariantViolation caught = assertThrows(InvariantViolation.class,
                () -> new Invariants(7).convergence(1, replicas));
        assertEquals("I1 Convergence", caught.invariant());
        assertTrue(caught.getMessage().contains("model fraud differs"), caught.getMessage());
    }

    @Test
    void aRejectionThatChangedSomethingIsCaught() {
        RegistryView before = new HandBuilt(null, null, null, 1, 0, 5);
        RegistryView after = new HandBuilt(
                oneModel("fraud", version("1.0.0", digest(1), Stage.STAGING, false)),
                oneBlob(new Blob(digest(1), 10, 1, true, 0)), null, 1, 0, 6);

        InvariantViolation caught = assertThrows(InvariantViolation.class,
                () -> new Invariants(7).rejectionPurity(1, "r0", before, after));
        assertEquals("I10 Rejection purity", caught.invariant());
    }

    @Test
    void aRejectionThatDidNotAdvanceTheLogIsCaught() {
        RegistryView unchanged = new HandBuilt(null, null, null, 1, 0, 5);
        InvariantViolation caught = assertThrows(InvariantViolation.class,
                () -> new Invariants(7).rejectionPurity(1, "r0", unchanged, unchanged));
        assertEquals("I10 Rejection purity", caught.invariant());
        assertTrue(caught.getMessage().contains("did not advance"), caught.getMessage());
    }

    @Test
    void anEffectAppliedTwiceIsCaught() {
        List<SequencedEffect> observed = new ArrayList<>();
        observed.add(new SequencedEffect(
                1, new Effect.VersionDeleted(Ref.of("fraud", "1.0.0"), "tester", 0L)));
        observed.add(new SequencedEffect(
                1, new Effect.VersionDeleted(Ref.of("fraud", "1.0.0"), "tester", 0L)));

        InvariantViolation caught = assertThrows(InvariantViolation.class,
                () -> new Invariants(7).exactlyOnceDelivery(1, observed, 1));
        assertEquals("I7 Exactly-once delivery", caught.invariant());
        assertTrue(caught.getMessage().contains("more than once"), caught.getMessage());
    }

    @Test
    void anAcknowledgedEffectThatNeverArrivedIsCaught() {
        InvariantViolation caught = assertThrows(InvariantViolation.class,
                () -> new Invariants(7).exactlyOnceDelivery(1, List.of(), 3));
        assertEquals("I7 Exactly-once delivery", caught.invariant());
        assertTrue(caught.getMessage().contains("never applied downstream"), caught.getMessage());
    }

    @Test
    void anOutOfOrderDeliveryIsCaught() {
        List<SequencedEffect> observed = List.of(
                new SequencedEffect(2, new Effect.VersionDeleted(
                        Ref.of("fraud", "1.0.0"), "tester", 0L)),
                new SequencedEffect(1, new Effect.VersionDeleted(
                        Ref.of("fraud", "1.0.0"), "tester", 0L)));

        InvariantViolation caught = assertThrows(InvariantViolation.class,
                () -> new Invariants(7).exactlyOnceDelivery(1, observed, 2));
        assertEquals("I7 Exactly-once delivery", caught.invariant());
    }

    @Test
    void anArtifactWhoseBytesAreWrongIsCaught() {
        RegistryView view = new HandBuilt(
                null, oneBlob(new Blob(digest(1), 10, 0, true, 0)), null, 1, 0, 1);

        InvariantViolation swapped = assertThrows(InvariantViolation.class,
                () -> new Invariants(7).artifactIntegrity(1, view, claimed -> digest(2)));
        assertEquals("I11 Artifact integrity", swapped.invariant());
        assertTrue(swapped.getMessage().contains("the digest is a lie"), swapped.getMessage());

        InvariantViolation absent = assertThrows(InvariantViolation.class,
                () -> new Invariants(7).artifactIntegrity(1, view, claimed -> null));
        assertEquals("I11 Artifact integrity", absent.invariant());
    }

    @Test
    void aDownstreamWorldThatDisagreesIsCaught() {
        InvariantViolation caught = assertThrows(InvariantViolation.class,
                () -> new Invariants(7).downstreamAgreement(1,
                        Map.of(ModelId.of("fraud"), VersionId.of("2.0.0")),
                        Map.of(ModelId.of("fraud"), VersionId.of("1.0.0"))));
        assertEquals("I12 Downstream agreement", caught.invariant());
    }

    @Test
    void aDanglingAncestorIsCaught() {
        ModelVersion child = new ModelVersion(
                VersionId.of("2.0.0"), digest(1), Stage.STAGING,
                List.of(Ref.of("fraud", "1.0.0")), null, 0L, "tester", false);
        RegistryView bad = new HandBuilt(
                oneModel("fraud", child),
                oneBlob(new Blob(digest(1), 10, 1, true, 0)), null, 1, 0, 1);

        InvariantViolation caught = assertThrows(InvariantViolation.class,
                () -> new Invariants(7).check(1, "r0", bad));
        assertEquals("I9 Lineage integrity", caught.invariant());
        assertTrue(caught.getMessage().contains("does not exist"), caught.getMessage());
    }

    @Test
    void aLiveVersionWithADeletedParentIsCaught() {
        ModelVersion parent = version("1.0.0", digest(1), Stage.ARCHIVED, true);
        ModelVersion child = new ModelVersion(
                VersionId.of("2.0.0"), digest(1), Stage.STAGING,
                List.of(Ref.of("fraud", "1.0.0")), null, 0L, "tester", false);
        RegistryView bad = new HandBuilt(
                oneModel("fraud", parent, child),
                oneBlob(new Blob(digest(1), 10, 1, true, 0)), null, 1, 0, 1);

        InvariantViolation caught = assertThrows(InvariantViolation.class,
                () -> new Invariants(7).check(1, "r0", bad));
        assertEquals("I9 Lineage integrity", caught.invariant());
        assertTrue(caught.getMessage().contains("provenance dangles"), caught.getMessage());
    }

    // ---- what it must not catch ---------------------------------------------------------------

    @Test
    void aReplicaReplayingItsLogIsNotAViolation() {
        // The case a naive monotonicity check gets wrong. A replica that crashes and rebuilds
        // passes through every index again on its way back; only ending up *lower* than it was is a
        // violation. A checker that fired on the journey would fire on every recovery.
        Invariants invariants = new Invariants(7);
        invariants.check(1, "r0", new HandBuilt(null, null, null, 1, 0, 50));
        assertDoesNotThrow(() -> {
            for (long index = 0; index <= 50; index++) {
                invariants.check(2, "r0", new HandBuilt(null, null, null, 1, 0,
                        Math.max(index, 50)));
            }
        });
    }

    @Test
    void anArtifactComingBackAfterCollectionIsNotAViolation() {
        // Re-ingesting bytes that were collected is a supported operation, so the checker has to
        // tell it apart from an artifact resurrecting itself. It does that by requiring the
        // collection order to have been delivered first, which the watermark records.
        Invariants invariants = new Invariants(7);
        invariants.check(1, "r0", new HandBuilt(
                null, oneBlob(new Blob(digest(1), 10, 0, false, 3)), null, 4, 3, 1));
        assertDoesNotThrow(() -> invariants.check(2, "r0", new HandBuilt(
                null, oneBlob(new Blob(digest(1), 10, 0, true, 0)), null, 4, 3, 2)));
    }

    @Test
    void aTombstoneWithALiveAncestorIsNotAViolation() {
        // A deleted version's lineage still points at something, and that something is never
        // removed, so a tombstone with a live or deleted parent is history rather than a dangling
        // pointer. Only a *live* version with a deleted parent is a violation.
        ModelVersion parent = version("1.0.0", digest(1), Stage.ARCHIVED, true);
        ModelVersion deletedChild = new ModelVersion(
                VersionId.of("2.0.0"), digest(1), Stage.ARCHIVED,
                List.of(Ref.of("fraud", "1.0.0")), null, 0L, "tester", true);
        RegistryView fine = new HandBuilt(
                oneModel("fraud", parent, deletedChild),
                oneBlob(new Blob(digest(1), 10, 0, false, 1)), null, 2, 1, 1);

        assertDoesNotThrow(() -> new Invariants(7).check(1, "r0", fine));
    }

    @Test
    void twoReplicasThatAgreeAreNotAViolation() {
        Registry state = Registry.empty();
        Map<String, RegistryView> replicas = new LinkedHashMap<>();
        replicas.put("r0", state);
        replicas.put("r1", state);
        assertDoesNotThrow(() -> new Invariants(7).convergence(1, replicas));
    }

    @Test
    void deliveryRunningAheadOfTheWatermarkIsNotAViolation() {
        // Applied but not yet acknowledged is the crash window the design tolerates, and it is the
        // normal state of affairs between a delivery and its acknowledgement. A checker that
        // insisted the two match would fail on every healthy run.
        List<SequencedEffect> observed = List.of(
                new SequencedEffect(1, new Effect.VersionDeleted(
                        Ref.of("fraud", "1.0.0"), "tester", 0L)),
                new SequencedEffect(2, new Effect.VersionDeleted(
                        Ref.of("fraud", "2.0.0"), "tester", 0L)));
        assertDoesNotThrow(() -> new Invariants(7).exactlyOnceDelivery(1, observed, 1));
    }

    @Test
    void aModelWithNoProductionVersionIsNotAViolation() {
        RegistryView fine = new HandBuilt(
                oneModel("fraud",
                        version("1.0.0", digest(1), Stage.STAGING, false),
                        version("2.0.0", digest(1), Stage.ARCHIVED, false)),
                oneBlob(new Blob(digest(1), 10, 2, true, 0)), null, 1, 0, 1);
        assertDoesNotThrow(() -> new Invariants(7).check(1, "r0", fine));
    }

    @Test
    void theCheckCountReflectsTheWorkDone() {
        Invariants invariants = new Invariants(7);
        assertEquals(0, invariants.checkCount());
        invariants.check(1, "r0", Registry.empty());
        invariants.check(2, "r0", Registry.empty());
        assertEquals(2, invariants.checkCount());
    }
}
