package io.cairn.core;

import static io.cairn.core.Fixtures.ACTOR;
import static io.cairn.core.Fixtures.applyAll;
import static io.cairn.core.Fixtures.delete;
import static io.cairn.core.Fixtures.digest;
import static io.cairn.core.Fixtures.ingest;
import static io.cairn.core.Fixtures.promote;
import static io.cairn.core.Fixtures.publish;
import static io.cairn.core.Fixtures.ref;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rules, one at a time.
 *
 * <p>Every rejection test also asserts that the state did not change, because "it said no" and "it
 * said no and changed nothing" are different claims and only the second one is useful.
 */
class KernelTest {

    private static Outcome.Rejected rejection(Transition transition) {
        return assertInstanceOf(Outcome.Rejected.class, transition.outcome(),
                "expected a rejection, got " + transition.outcome());
    }

    private static Outcome.Applied applied(Transition transition) {
        return assertInstanceOf(Outcome.Applied.class, transition.outcome(),
                "expected acceptance, got " + transition.outcome());
    }

    /** Applies one command to a state and asserts the domain state is untouched. */
    private static Transition rejects(Registry before, Command command, RejectionCode expected) {
        Transition after = Kernel.apply(before, before.appliedIndex() + 1, command);
        assertEquals(expected, rejection(after).code(),
                "wrong rejection code: " + after.outcome());
        assertTrue(RegistryView.sameState(before, after.state()),
                "a rejected command changed the state: " + rejection(after).detail());
        assertEquals(before.appliedIndex() + 1, after.state().appliedIndex(),
                "a rejected command must still advance the log position");
        return after;
    }

    @Nested
    @DisplayName("the log contract")
    class LogContract {

        @Test
        void anIndexAlreadyAppliedChangesNothing() {
            Registry state = applyAll(ingest(digest(1), 10));
            Transition again = Kernel.apply(state, state.appliedIndex(), ingest(digest(2), 20));

            assertEquals(Outcome.Applied.Kind.DUPLICATE, applied(again).kind());
            assertSame(state, again.state(), "a duplicate index must return the same state");
        }

        @Test
        void aGapInTheLogIsNotSomethingToRecoverFrom() {
            Registry state = Registry.empty();
            IllegalStateException gap = assertThrows(
                    IllegalStateException.class,
                    () -> Kernel.apply(state, 5, ingest(digest(1), 10)));
            assertTrue(gap.getMessage().contains("log gap"), gap.getMessage());
        }

        @Test
        void theSameCommandsProduceTheSameState() {
            Command[] script = {
                ingest(digest(1), 100),
                ingest(digest(2), 200),
                publish(ref("fraud", "1.0.0"), digest(1)),
                publish(ref("fraud", "1.1.0"), digest(2), List.of(ref("fraud", "1.0.0"))),
                promote(ref("fraud", "1.0.0"), Stage.PRODUCTION),
                promote(ref("fraud", "1.1.0"), Stage.PRODUCTION),
            };
            assertEquals(applyAll(script), applyAll(script),
                    "the kernel is not a function of its inputs");
        }
    }

    @Nested
    @DisplayName("ingesting artifacts")
    class Ingest {

        @Test
        void anArtifactIsRecordedOnce() {
            Registry state = applyAll(ingest(digest(1), 4096));
            Blob blob = state.blob(digest(1)).orElseThrow();

            assertEquals(4096, blob.size());
            assertEquals(0, blob.refCount());
            assertTrue(blob.present());
        }

        @Test
        void ingestingTheSameArtifactTwiceIsARetry() {
            Registry once = applyAll(ingest(digest(1), 4096));
            Transition twice = Kernel.apply(once, 2, ingest(digest(1), 4096));

            assertEquals(Outcome.Applied.Kind.IDEMPOTENT, applied(twice).kind());
            assertTrue(RegistryView.sameState(once, twice.state()));
        }

        @Test
        void oneDigestWithTwoLengthsMeansSomethingUpstreamIsBroken() {
            Registry state = applyAll(ingest(digest(1), 4096));
            rejects(state, ingest(digest(1), 4097), RejectionCode.DIGEST_SIZE_MISMATCH);
        }
    }

    @Nested
    @DisplayName("publishing versions")
    class Publish {

        @Test
        void aPublishedVersionIsStagedNotLive() {
            Registry state = applyAll(
                    ingest(digest(1), 10), publish(ref("fraud", "1.0.0"), digest(1)));
            ModelVersion version = state.version(ref("fraud", "1.0.0")).orElseThrow();

            assertEquals(Stage.STAGING, version.stage(),
                    "publishing must not put a version into production");
            assertEquals(digest(1), version.artifact());
            assertEquals(1, state.blob(digest(1)).orElseThrow().refCount());
        }

        @Test
        void publishingProducesExactlyOneEffect() {
            Registry state = applyAll(ingest(digest(1), 10));
            Transition after = Kernel.apply(state, 2, publish(ref("fraud", "1.0.0"), digest(1)));

            List<SequencedEffect> effects = applied(after).effects();
            assertEquals(1, effects.size(), effects.toString());
            assertEquals(1, effects.get(0).seq(), "effect sequence numbers start at 1");
            assertInstanceOf(Effect.VersionPublished.class, effects.get(0).effect());
        }

        @Test
        void anArtifactThatWasNeverIngestedCannotBePublished() {
            rejects(Registry.empty(), publish(ref("fraud", "1.0.0"), digest(1)),
                    RejectionCode.ARTIFACT_MISSING);
        }

        @Test
        void republishingTheSameThingIsARetryNotAConflict() {
            Registry state = applyAll(
                    ingest(digest(1), 10), publish(ref("fraud", "1.0.0"), digest(1)));
            Transition again = Kernel.apply(state, 3, publish(ref("fraud", "1.0.0"), digest(1)));

            assertEquals(Outcome.Applied.Kind.IDEMPOTENT, applied(again).kind());
            assertTrue(applied(again).effects().isEmpty(), "a retry must not re-emit effects");
            assertTrue(RegistryView.sameState(state, again.state()));
            assertEquals(1, again.state().blob(digest(1)).orElseThrow().refCount(),
                    "a retry must not take a second reference on the artifact");
        }

        @Test
        void aRetryThatListsItsParentsInAnotherOrderIsStillARetry() {
            Registry state = applyAll(
                    ingest(digest(1), 10),
                    ingest(digest(2), 20),
                    ingest(digest(3), 30),
                    publish(ref("fraud", "1.0.0"), digest(1)),
                    publish(ref("fraud", "1.1.0"), digest(2)),
                    publish(ref("fraud", "2.0.0"), digest(3),
                            List.of(ref("fraud", "1.0.0"), ref("fraud", "1.1.0"))));

            Transition reordered = Kernel.apply(state, state.appliedIndex() + 1,
                    publish(ref("fraud", "2.0.0"), digest(3),
                            List.of(ref("fraud", "1.1.0"), ref("fraud", "1.0.0"))));

            assertEquals(Outcome.Applied.Kind.IDEMPOTENT, applied(reordered).kind(),
                    "parent order carries no meaning, so it must not decide idempotence");
        }

        @Test
        void aPublishedVersionCannotBeGivenDifferentContent() {
            Registry state = applyAll(
                    ingest(digest(1), 10),
                    ingest(digest(2), 20),
                    publish(ref("fraud", "1.0.0"), digest(1)));

            rejects(state, publish(ref("fraud", "1.0.0"), digest(2)),
                    RejectionCode.IMMUTABLE_VERSION);
            assertEquals(digest(1), state.version(ref("fraud", "1.0.0")).orElseThrow().artifact());
        }

        @Test
        void aPublishedVersionCannotBeGivenDifferentLabels() {
            Registry state = applyAll(
                    ingest(digest(1), 10),
                    new Command.PublishVersion(ref("fraud", "1.0.0"), digest(1), List.of(),
                            ModelVersion.labels("owner", "risk"), ACTOR, 1L));

            rejects(state,
                    new Command.PublishVersion(ref("fraud", "1.0.0"), digest(1), List.of(),
                            ModelVersion.labels("owner", "growth"), ACTOR, 2L),
                    RejectionCode.IMMUTABLE_VERSION);
        }

        @Test
        void aRetryDoesNotRewriteWhoPublishedIt() {
            Registry state = applyAll(
                    ingest(digest(1), 10),
                    new Command.PublishVersion(
                            ref("fraud", "1.0.0"), digest(1), List.of(), null, "alice", 111L));

            Transition retry = Kernel.apply(state, 3, new Command.PublishVersion(
                    ref("fraud", "1.0.0"), digest(1), List.of(), null, "bob", 222L));

            assertEquals(Outcome.Applied.Kind.IDEMPOTENT, applied(retry).kind());
            ModelVersion version = retry.state().version(ref("fraud", "1.0.0")).orElseThrow();
            assertEquals("alice", version.publishedBy(),
                    "the first publish is the one that happened");
            assertEquals(111L, version.publishedAt());
        }

        @Test
        void aDeletedVersionIdentifierIsSpent() {
            Registry state = applyAll(
                    ingest(digest(1), 10),
                    publish(ref("fraud", "1.0.0"), digest(1)),
                    delete(ref("fraud", "1.0.0")),
                    // The delete released the artifact, so it cannot be re-ingested until the
                    // collection order has been delivered. Acknowledging it here is the test
                    // saying "the collector has run".
                    new Command.AckEffects(3),
                    ingest(digest(1), 10));

            rejects(state, publish(ref("fraud", "1.0.0"), digest(1)),
                    RejectionCode.VERSION_DELETED);
        }
    }

    @Nested
    @DisplayName("lineage")
    class Lineage {

        @Test
        void aParentThatDoesNotExistIsRefused() {
            Registry state = applyAll(ingest(digest(1), 10));
            rejects(state,
                    publish(ref("fraud", "2.0.0"), digest(1), List.of(ref("fraud", "1.0.0"))),
                    RejectionCode.UNKNOWN_PARENT);
        }

        @Test
        void aParentInAnotherModelIsFine() {
            Registry state = applyAll(
                    ingest(digest(1), 10),
                    ingest(digest(2), 20),
                    publish(ref("embeddings", "1.0.0"), digest(1)),
                    publish(ref("fraud", "1.0.0"), digest(2), List.of(ref("embeddings", "1.0.0"))));

            assertEquals(List.of(ref("embeddings", "1.0.0")),
                    state.version(ref("fraud", "1.0.0")).orElseThrow().parents());
        }

        @Test
        void aVersionCannotDescendFromItself() {
            Registry state = applyAll(ingest(digest(1), 10));
            rejects(state,
                    publish(ref("fraud", "1.0.0"), digest(1), List.of(ref("fraud", "1.0.0"))),
                    RejectionCode.SELF_PARENT);
        }

        @Test
        void aDeletedVersionCannotBecomeAParent() {
            Registry state = applyAll(
                    ingest(digest(1), 10),
                    ingest(digest(2), 20),
                    publish(ref("fraud", "1.0.0"), digest(1)),
                    delete(ref("fraud", "1.0.0")));

            rejects(state,
                    publish(ref("fraud", "2.0.0"), digest(2), List.of(ref("fraud", "1.0.0"))),
                    RejectionCode.PARENT_DELETED);
        }

        @Test
        void aVersionWithLiveDescendantsCannotBeDeleted() {
            Registry state = applyAll(
                    ingest(digest(1), 10),
                    ingest(digest(2), 20),
                    publish(ref("fraud", "1.0.0"), digest(1)),
                    publish(ref("fraud", "2.0.0"), digest(2), List.of(ref("fraud", "1.0.0"))));

            Transition refused = rejects(state, delete(ref("fraud", "1.0.0")),
                    RejectionCode.HAS_DESCENDANTS);
            assertTrue(refused.state().version(ref("fraud", "1.0.0")).orElseThrow().live());
        }

        @Test
        void deletingTheDescendantFirstReleasesTheAncestor() {
            Registry state = applyAll(
                    ingest(digest(1), 10),
                    ingest(digest(2), 20),
                    publish(ref("fraud", "1.0.0"), digest(1)),
                    publish(ref("fraud", "2.0.0"), digest(2), List.of(ref("fraud", "1.0.0"))),
                    delete(ref("fraud", "2.0.0")),
                    delete(ref("fraud", "1.0.0")));

            assertFalse(state.version(ref("fraud", "1.0.0")).orElseThrow().live());
        }
    }

    @Nested
    @DisplayName("stages")
    class Stages {

        @Test
        void promotingToProductionAnnouncesTheChange() {
            Registry state = applyAll(
                    ingest(digest(1), 10), publish(ref("fraud", "1.0.0"), digest(1)));
            Transition after = Kernel.apply(state, 3, promote(ref("fraud", "1.0.0"), Stage.PRODUCTION));

            List<SequencedEffect> effects = applied(after).effects();
            assertEquals(2, effects.size(), effects.toString());
            assertInstanceOf(Effect.StageChanged.class, effects.get(0).effect());
            assertInstanceOf(Effect.ProductionChanged.class, effects.get(1).effect());
            assertEquals(VersionId.of("1.0.0"),
                    ((Effect.ProductionChanged) effects.get(1).effect()).production());
        }

        @Test
        void promotingASuccessorDemotesTheIncumbentInTheSameTransition() {
            Registry state = applyAll(
                    ingest(digest(1), 10),
                    ingest(digest(2), 20),
                    publish(ref("fraud", "1.0.0"), digest(1)),
                    publish(ref("fraud", "2.0.0"), digest(2)),
                    promote(ref("fraud", "1.0.0"), Stage.PRODUCTION));

            Transition after = Kernel.apply(state, state.appliedIndex() + 1,
                    promote(ref("fraud", "2.0.0"), Stage.PRODUCTION));

            List<SequencedEffect> effects = applied(after).effects();
            assertEquals(3, effects.size(), effects.toString());

            Effect.StageChanged demotion = assertInstanceOf(
                    Effect.StageChanged.class, effects.get(0).effect());
            assertEquals(ref("fraud", "1.0.0"), demotion.ref(),
                    "the incumbent must be demoted before the successor is promoted");
            assertEquals(Stage.ARCHIVED, demotion.to());

            Model model = after.state().model(ModelId.of("fraud")).orElseThrow();
            assertEquals(VersionId.of("2.0.0"), model.production().orElseThrow().version());
            assertEquals(Stage.ARCHIVED,
                    model.version(VersionId.of("1.0.0")).orElseThrow().stage());
        }

        @Test
        void leavingProductionAnnouncesThatThereIsNoneLeft() {
            Registry state = applyAll(
                    ingest(digest(1), 10),
                    publish(ref("fraud", "1.0.0"), digest(1)),
                    promote(ref("fraud", "1.0.0"), Stage.PRODUCTION));

            Transition after = Kernel.apply(state, state.appliedIndex() + 1,
                    promote(ref("fraud", "1.0.0"), Stage.DEPRECATED));

            Effect.ProductionChanged announced = assertInstanceOf(
                    Effect.ProductionChanged.class, applied(after).effects().get(1).effect());
            assertEquals(null, announced.production());
            assertTrue(after.state().model(ModelId.of("fraud")).orElseThrow()
                    .production().isEmpty());
        }

        @Test
        void aStagedVersionCannotBeDeprecatedDirectly() {
            Registry state = applyAll(
                    ingest(digest(1), 10), publish(ref("fraud", "1.0.0"), digest(1)));
            rejects(state, promote(ref("fraud", "1.0.0"), Stage.DEPRECATED),
                    RejectionCode.ILLEGAL_TRANSITION);
        }

        @Test
        void deprecationIsTerminal() {
            Registry state = applyAll(
                    ingest(digest(1), 10),
                    publish(ref("fraud", "1.0.0"), digest(1)),
                    promote(ref("fraud", "1.0.0"), Stage.ARCHIVED),
                    promote(ref("fraud", "1.0.0"), Stage.DEPRECATED));

            for (Stage target : Stage.values()) {
                if (target == Stage.DEPRECATED) {
                    continue;
                }
                rejects(state, promote(ref("fraud", "1.0.0"), target),
                        RejectionCode.ILLEGAL_TRANSITION);
            }
        }

        @Test
        void promotingToTheStageItIsAlreadyInIsARetry() {
            Registry state = applyAll(
                    ingest(digest(1), 10),
                    publish(ref("fraud", "1.0.0"), digest(1)),
                    promote(ref("fraud", "1.0.0"), Stage.PRODUCTION));

            Transition again = Kernel.apply(state, state.appliedIndex() + 1,
                    promote(ref("fraud", "1.0.0"), Stage.PRODUCTION));

            assertEquals(Outcome.Applied.Kind.IDEMPOTENT, applied(again).kind());
            assertTrue(applied(again).effects().isEmpty());
        }

        @Test
        void anUnknownModelAndAnUnknownVersionAreDifferentAnswers() {
            Registry state = applyAll(
                    ingest(digest(1), 10), publish(ref("fraud", "1.0.0"), digest(1)));

            rejects(state, promote(ref("churn", "1.0.0"), Stage.PRODUCTION),
                    RejectionCode.UNKNOWN_MODEL);
            rejects(state, promote(ref("fraud", "9.9.9"), Stage.PRODUCTION),
                    RejectionCode.UNKNOWN_VERSION);
        }
    }

    @Nested
    @DisplayName("deletion and collection")
    class Collection {

        @Test
        void theProductionVersionCannotBeDeleted() {
            Registry state = applyAll(
                    ingest(digest(1), 10),
                    publish(ref("fraud", "1.0.0"), digest(1)),
                    promote(ref("fraud", "1.0.0"), Stage.PRODUCTION));

            rejects(state, delete(ref("fraud", "1.0.0")), RejectionCode.PRODUCTION_VERSION);
        }

        @Test
        void theLastReferenceReleasesTheArtifact() {
            Registry state = applyAll(
                    ingest(digest(1), 4096), publish(ref("fraud", "1.0.0"), digest(1)));
            Transition after = Kernel.apply(state, 3, delete(ref("fraud", "1.0.0")));

            List<SequencedEffect> effects = applied(after).effects();
            assertEquals(2, effects.size(), effects.toString());
            Effect.ArtifactCollected collected = assertInstanceOf(
                    Effect.ArtifactCollected.class, effects.get(1).effect());
            assertEquals(digest(1), collected.digest());
            assertEquals(4096, collected.size());

            Blob blob = after.state().blob(digest(1)).orElseThrow();
            assertFalse(blob.present(), "a collected artifact must be marked absent");
            assertEquals(0, blob.refCount());
        }

        @Test
        void anArtifactSharedByTwoVersionsSurvivesTheFirstDeletion() {
            Registry state = applyAll(
                    ingest(digest(1), 4096),
                    publish(ref("fraud", "1.0.0"), digest(1)),
                    publish(ref("fraud", "1.0.1"), digest(1)));

            assertEquals(2, state.blob(digest(1)).orElseThrow().refCount());

            Transition first = Kernel.apply(state, state.appliedIndex() + 1,
                    delete(ref("fraud", "1.0.0")));
            assertEquals(1, applied(first).effects().size(),
                    "nothing should be collected while a reference remains");
            assertTrue(first.state().blob(digest(1)).orElseThrow().present());

            Transition second = Kernel.apply(first.state(), first.state().appliedIndex() + 1,
                    delete(ref("fraud", "1.0.1")));
            assertEquals(2, applied(second).effects().size());
            assertFalse(second.state().blob(digest(1)).orElseThrow().present());
        }

        @Test
        void aCollectedArtifactCannotBePublishedAgainstUntilItIsBack() {
            Registry collected = applyAll(
                    ingest(digest(1), 10),
                    publish(ref("fraud", "1.0.0"), digest(1)),
                    delete(ref("fraud", "1.0.0")));

            rejects(collected, publish(ref("fraud", "2.0.0"), digest(1)),
                    RejectionCode.ARTIFACT_MISSING);

            Registry reingested = applyAll(
                    ingest(digest(1), 10),
                    publish(ref("fraud", "1.0.0"), digest(1)),
                    delete(ref("fraud", "1.0.0")),
                    new Command.AckEffects(3),
                    ingest(digest(1), 10),
                    publish(ref("fraud", "2.0.0"), digest(1)));

            assertTrue(reingested.blob(digest(1)).orElseThrow().present());
            assertEquals(1, reingested.blob(digest(1)).orElseThrow().refCount());
            assertEquals(0, reingested.blob(digest(1)).orElseThrow().collectSeq(),
                    "coming back must clear the collection order");
        }

        @Test
        void anArtifactCannotComeBackWhileItsCollectionOrderIsStillInFlight() {
            // The hole a simulation found, as a test. Without the collectSeq guard this sequence
            // was: delete the last version, re-ingest the bytes, publish against them, and then
            // the collector finally runs and deletes an artifact a live version points at.
            Registry state = applyAll(
                    ingest(digest(1), 10),
                    publish(ref("fraud", "1.0.0"), digest(1)),
                    delete(ref("fraud", "1.0.0")));

            Blob artifact = state.blob(digest(1)).orElseThrow();
            assertFalse(artifact.present());
            // Effect #1 was the publish; the delete produced #2 VersionDeleted and #3
            // ArtifactCollected, so the collection order this artifact records is #3.
            assertEquals(3, artifact.collectSeq());
            assertTrue(state.dispatchedThrough() < artifact.collectSeq(),
                    "and it has not been delivered yet");

            Transition refused = rejects(state, ingest(digest(1), 10),
                    RejectionCode.COLLECTION_PENDING);
            assertTrue(rejection(refused).detail().contains("collection order"),
                    rejection(refused).detail());

            // Once the collector has run, the bytes may come back.
            Transition acked = Kernel.apply(refused.state(),
                    refused.state().appliedIndex() + 1, new Command.AckEffects(3));
            Transition back = Kernel.apply(acked.state(),
                    acked.state().appliedIndex() + 1, ingest(digest(1), 10));
            assertTrue(back.outcome().accepted(), back.outcome().toString());
            assertTrue(back.state().blob(digest(1)).orElseThrow().present());
        }

        @Test
        void deletingTwiceIsARetry() {
            Registry state = applyAll(
                    ingest(digest(1), 10),
                    publish(ref("fraud", "1.0.0"), digest(1)),
                    delete(ref("fraud", "1.0.0")));

            Transition again = Kernel.apply(state, state.appliedIndex() + 1,
                    delete(ref("fraud", "1.0.0")));

            assertEquals(Outcome.Applied.Kind.IDEMPOTENT, applied(again).kind());
            assertTrue(applied(again).effects().isEmpty(),
                    "a repeated deletion must not order the artifact collected twice");
        }
    }

    @Nested
    @DisplayName("the effect watermark")
    class Watermark {

        private Registry withThreeEffects() {
            return applyAll(
                    ingest(digest(1), 10),
                    publish(ref("fraud", "1.0.0"), digest(1)),
                    promote(ref("fraud", "1.0.0"), Stage.PRODUCTION));
        }

        @Test
        void acknowledgingDropsTheDeliveredPrefix() {
            Registry state = withThreeEffects();
            assertEquals(3, state.outboxDepth());

            Transition after = Kernel.apply(state, state.appliedIndex() + 1,
                    new Command.AckEffects(2));

            assertEquals(2, after.state().dispatchedThrough());
            assertEquals(1, after.state().outboxDepth());
            assertEquals(3, after.state().outbox().get(0).seq());
        }

        @Test
        void acknowledgingBelowTheWatermarkIsARetry() {
            Registry state = withThreeEffects();
            Transition acked = Kernel.apply(state, state.appliedIndex() + 1,
                    new Command.AckEffects(3));
            Transition again = Kernel.apply(acked.state(), acked.state().appliedIndex() + 1,
                    new Command.AckEffects(1));

            assertEquals(Outcome.Applied.Kind.IDEMPOTENT, applied(again).kind());
            assertEquals(3, again.state().dispatchedThrough(),
                    "the watermark must not move backwards");
        }

        @Test
        void acknowledgingSomethingNeverProducedIsRefused() {
            Registry state = withThreeEffects();
            rejects(state, new Command.AckEffects(4), RejectionCode.ACK_AHEAD_OF_LOG);
        }

        @Test
        void anAcknowledgementProducesNoEffectOfItsOwn() {
            Registry state = withThreeEffects();
            Transition after = Kernel.apply(state, state.appliedIndex() + 1,
                    new Command.AckEffects(3));

            assertTrue(applied(after).effects().isEmpty(),
                    "an effect for an acknowledgement would need acknowledging in turn");
            assertTrue(after.state().outbox().isEmpty());
        }

        @Test
        void sequenceNumbersAreContiguousAcrossCommands() {
            Registry state = withThreeEffects();
            long expected = 1;
            for (SequencedEffect effect : state.outbox()) {
                assertEquals(expected++, effect.seq());
            }
            assertEquals(expected, state.nextEffectSeq());
        }
    }
}
