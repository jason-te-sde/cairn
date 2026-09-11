package io.cairn.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The value types' own rules: the ones that make an invalid state unrepresentable. */
class ValueTypeTest {

    @Test
    void aDigestIsLowercaseHexOfTheRightLength() {
        String hex = "a".repeat(64);
        assertEquals(hex, new Digest("sha256", hex).hex());
        assertThrows(IllegalArgumentException.class, () -> new Digest("sha256", hex.toUpperCase()));
        assertThrows(IllegalArgumentException.class, () -> new Digest("sha256", "a".repeat(63)));
        assertThrows(IllegalArgumentException.class, () -> new Digest("sha1", hex));
        assertThrows(IllegalArgumentException.class, () -> new Digest("sha256", "g".repeat(64)));
    }

    @Test
    void aDigestRoundTripsThroughItsTextForm() {
        Digest digest = Fixtures.digest(7);
        assertEquals(digest, Digest.parse(digest.toString()));
        assertEquals(12, digest.shortHex().length());
    }

    @Test
    void rawHashBytesBecomeADigest() {
        byte[] hash = new byte[32];
        hash[0] = (byte) 0xAB;
        hash[31] = 0x0F;
        assertTrue(Digest.ofSha256(hash).hex().startsWith("ab"));
        assertTrue(Digest.ofSha256(hash).hex().endsWith("0f"));
        assertThrows(IllegalArgumentException.class, () -> Digest.ofSha256(new byte[31]));
    }

    @Test
    void anAbsentArtifactCannotBeReferenced() {
        // The record refuses the state the delete path exists to prevent, so a future change that
        // breaks the ordering fails where it constructed the bad value rather than later.
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new Blob(Fixtures.digest(1), 10, 1, false, 0));
        assertTrue(refused.getMessage().contains("still referenced"), refused.getMessage());
    }

    @Test
    void aPresentArtifactCannotHaveACollectionOrderAgainstIt() {
        // The other half of the same guard. An artifact whose bytes are back while a collector is
        // still on its way to delete them is the dangling reference a simulation found; the record
        // refuses to represent it at all.
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new Blob(Fixtures.digest(1), 10, 0, true, 7));
        assertTrue(refused.getMessage().contains("collection order"), refused.getMessage());

        assertTrue(new Blob(Fixtures.digest(1), 10, 0, false, 7).reingestable(7));
        assertFalse(new Blob(Fixtures.digest(1), 10, 0, false, 7).reingestable(6));
    }

    @Test
    void parentsAreSortedAndDeduplicatedWhereverTheyAreBuilt() {
        List<Ref> declared = List.of(
                Ref.of("b", "1.0.0"), Ref.of("a", "2.0.0"), Ref.of("b", "1.0.0"));

        ModelVersion version = new ModelVersion(
                VersionId.of("3.0.0"), Fixtures.digest(1), Stage.STAGING, declared, null,
                0L, "tester", false);
        assertEquals(List.of(Ref.of("a", "2.0.0"), Ref.of("b", "1.0.0")), version.parents());

        Command.PublishVersion command = new Command.PublishVersion(
                Ref.of("c", "3.0.0"), Fixtures.digest(1), declared, null, "tester", 0L);
        assertEquals(version.parents(), command.parents(),
                "a command and the version it produces must normalize identically");
    }

    @Test
    void boundsAreEnforcedOnCollections() {
        List<Ref> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i <= Limits.MAX_PARENTS; i++) {
            tooMany.add(Ref.of("m" + i, "1.0.0"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ModelVersion(VersionId.of("1.0.0"), Fixtures.digest(1), Stage.STAGING,
                        tooMany, null, 0L, "tester", false));

        var tooManyLabels = new java.util.TreeMap<String, String>();
        for (int i = 0; i <= Limits.MAX_LABELS; i++) {
            tooManyLabels.put("k" + i, "v");
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ModelVersion(VersionId.of("1.0.0"), Fixtures.digest(1), Stage.STAGING,
                        null, tooManyLabels, 0L, "tester", false));
    }

    @Test
    void anEmptyRegistryIsAValidOne() {
        Registry empty = Registry.empty();
        assertEquals(1, empty.nextEffectSeq());
        assertEquals(0, empty.dispatchedThrough());
        assertEquals(0, empty.appliedIndex());
        assertTrue(empty.models().isEmpty());
        assertTrue(empty.outbox().isEmpty());
    }

    @Test
    void aWatermarkAheadOfTheSequenceIsNotAConstructibleRegistry() {
        assertThrows(IllegalArgumentException.class,
                () -> new Registry(null, null, null, 1, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Registry(null, null, null, 0, 0, 0));
    }

    @Test
    void aStageChangeMustChangeTheStage() {
        assertThrows(IllegalArgumentException.class, () -> new Effect.StageChanged(
                Ref.of("m", "1.0.0"), Stage.STAGING, Stage.STAGING, "tester", 0L));
    }

    @Test
    void onlyANewOutcomeMayCarryEffects() {
        List<SequencedEffect> effects = List.of(new SequencedEffect(
                1, new Effect.VersionDeleted(Ref.of("m", "1.0.0"), "tester", 0L)));
        assertThrows(IllegalArgumentException.class,
                () -> new Outcome.Applied(Outcome.Applied.Kind.IDEMPOTENT, effects));
        assertEquals(1, new Outcome.Applied(Outcome.Applied.Kind.NEW, effects).effects().size());
    }

    @Test
    void effectSequenceNumbersStartAtOne() {
        assertThrows(IllegalArgumentException.class, () -> new SequencedEffect(
                0, new Effect.VersionDeleted(Ref.of("m", "1.0.0"), "tester", 0L)));
    }

    @Test
    void aModelFindsItsProductionVersionByLookingRatherThanByRemembering() {
        Registry state = Fixtures.applyAll(
                Fixtures.ingest(Fixtures.digest(1), 10),
                Fixtures.publish(Ref.of("fraud", "1.0.0"), Fixtures.digest(1)),
                Fixtures.promote(Ref.of("fraud", "1.0.0"), Stage.PRODUCTION));

        Model model = state.model(ModelId.of("fraud")).orElseThrow();
        assertEquals(VersionId.of("1.0.0"), model.production().orElseThrow().version());
        assertEquals(1, model.liveCount());
        assertTrue(model.liveVersion(VersionId.of("9.9.9")).isEmpty());
        assertTrue(Model.empty(ModelId.of("empty")).production().isEmpty());
    }
}
