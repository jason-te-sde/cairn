package io.cairn.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cairn.core.Command;
import io.cairn.core.Digest;
import io.cairn.core.Effect;
import io.cairn.core.PureKernel;
import io.cairn.core.Ref;
import io.cairn.core.SequencedEffect;
import io.cairn.core.Stage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The dispatcher's ordering, acknowledgement and failure behaviour. */
class DispatcherTest {

    private PureKernel kernel;
    private long nextIndex;
    private RecordingSink downstream;
    private IdempotentSink sink;
    private Dispatcher dispatcher;

    @BeforeEach
    void setUp() {
        kernel = new PureKernel();
        nextIndex = 1;
        downstream = new RecordingSink();
        sink = new IdempotentSink(downstream, new InMemoryAppliedLedger());
        dispatcher = new Dispatcher(kernel::view, sink, this::propose);
    }

    private io.cairn.core.Outcome propose(Command command) {
        return kernel.apply(nextIndex++, command);
    }

    private static Digest digest(int n) {
        try {
            return Digest.ofSha256(MessageDigest.getInstance("SHA-256")
                    .digest(("artifact-" + n).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** Produces five effects: publish, publish, promote (two), promote (three). */
    private void produceEffects() {
        propose(new Command.IngestBlob(digest(1), 100));
        propose(new Command.IngestBlob(digest(2), 200));
        propose(Command.PublishVersion.of(Ref.of("fraud", "1.0.0"), digest(1), "alice", 1L));
        propose(Command.PublishVersion.of(Ref.of("fraud", "2.0.0"), digest(2), "alice", 2L));
        propose(new Command.Promote(Ref.of("fraud", "1.0.0"), Stage.PRODUCTION, "alice", 3L));
        propose(new Command.Promote(Ref.of("fraud", "2.0.0"), Stage.PRODUCTION, "alice", 4L));
    }

    @Test
    void anEmptyOutboxIsNothingToDo() {
        Dispatcher.Run run = dispatcher.drain();

        assertEquals(0, run.delivered());
        assertEquals(0, run.acknowledged());
        assertTrue(run.complete());
        assertEquals(0, dispatcher.lag());
    }

    @Test
    void effectsAreDeliveredInSequenceOrder() {
        produceEffects();
        int produced = kernel.view().outbox().size();
        assertTrue(produced >= 5, "the fixture should produce several effects, got " + produced);

        Dispatcher.Run run = dispatcher.drain();

        assertEquals(produced, run.delivered());
        long expected = 1;
        for (SequencedEffect received : downstream.received()) {
            assertEquals(expected++, received.seq(), "delivery must follow the sequence");
        }
    }

    @Test
    void oneAcknowledgementPerRunAtTheHighestSequence() {
        produceEffects();
        long highest = kernel.view().nextEffectSeq() - 1;

        Dispatcher.Run run = dispatcher.drain();

        assertEquals(highest, run.acknowledged());
        assertEquals(highest, kernel.view().dispatchedThrough());
        assertEquals(0, run.pending(), "the outbox should be empty afterwards");
        assertTrue(run.complete());
    }

    @Test
    void theAcknowledgedPrefixIsDroppedFromTheOutbox() {
        produceEffects();
        assertTrue(kernel.view().outbox().size() > 0);

        dispatcher.drain();

        assertTrue(kernel.view().outbox().isEmpty(),
                "the outbox is bounded by delivery lag, not by history");
    }

    @Test
    void aRefusalStopsTheRunRatherThanSkippingAhead() {
        produceEffects();
        downstream.failFrom(3);

        Dispatcher.Run run = dispatcher.drain();

        assertEquals(2, run.delivered());
        assertEquals(2, run.acknowledged(), "only what got through is acknowledged");
        assertNotNull(run.failure());
        assertFalse(run.complete());

        // Nothing above the failure reached the downstream world, and it is all still owed.
        assertEquals(List.of(1L, 2L), downstream.received().stream()
                .map(SequencedEffect::seq).toList());
        assertEquals(3, kernel.view().outbox().get(0).seq());
        assertTrue(dispatcher.lag() > 0);
    }

    @Test
    void aRecoveredSinkGetsTheRestWithoutRepeatingWhatItHad() {
        produceEffects();
        downstream.failFrom(3);
        dispatcher.drain();

        downstream.recover();
        Dispatcher.Run second = dispatcher.drain();

        assertTrue(second.complete(), "the outbox should drain once the sink is back");
        long expected = 1;
        for (SequencedEffect received : downstream.received()) {
            assertEquals(expected++, received.seq(),
                    "the downstream world must see each effect once, in order");
        }
        assertEquals(kernel.view().nextEffectSeq(), expected,
                "every effect produced should have been delivered exactly once");
    }

    @Test
    void aRedeliveryAfterALostAcknowledgementIsDroppedByTheConsumer() {
        // The crash window that makes this design necessary: the effects were delivered, the
        // process died before the acknowledgement was committed, so the registry still owes them.
        produceEffects();
        for (SequencedEffect effect : kernel.view().outbox()) {
            sink.deliver(effect);
        }
        int seenOnce = downstream.received().size();
        assertTrue(seenOnce > 0);
        assertEquals(0, kernel.view().dispatchedThrough(), "nothing was acknowledged");

        // A new dispatcher, after the restart, offers them all again.
        Dispatcher restarted = new Dispatcher(kernel::view, sink, this::propose);
        Dispatcher.Run run = restarted.drain();

        assertEquals(seenOnce, downstream.received().size(),
                "the downstream world must not see an effect twice");
        assertEquals(seenOnce, sink.duplicateCount(),
                "and every redelivery should have been counted as one");
        assertTrue(run.acknowledged() > 0, "the redelivery still advances the watermark");
    }

    @Test
    void anAcknowledgementAheadOfTheRegistryIsRefusedLoudlyAndChangesNothing() {
        produceEffects();
        long produced = kernel.view().nextEffectSeq() - 1;

        io.cairn.core.Outcome refused = propose(new Command.AckEffects(produced + 5));

        assertInstanceOf(io.cairn.core.Outcome.Rejected.class, refused);
        assertEquals(io.cairn.core.RejectionCode.ACK_AHEAD_OF_LOG,
                ((io.cairn.core.Outcome.Rejected) refused).code());
        assertEquals(0, kernel.view().dispatchedThrough(), "the watermark must not have moved");
    }

    @Test
    void drainAllStopsWhenARunMakesNoProgress() {
        produceEffects();
        downstream.failFrom(1);

        Dispatcher.Run run = dispatcher.drainAll();

        assertEquals(0, run.delivered());
        assertNotNull(run.failure());
        assertTrue(dispatcher.runCount() <= 2,
                "a broken sink must not become a busy wait, ran " + dispatcher.runCount()
                        + " times");
    }

    @Test
    void theDemotionIsDeliveredBeforeThePromotionThatCausedIt() {
        // The ordering guarantee that matters to a consumer: a serving tier replaying this stream
        // never holds two production versions, not even for one message.
        produceEffects();
        dispatcher.drain();

        List<Effect> promotions = downstream.received().stream()
                .map(SequencedEffect::effect)
                .filter(effect -> effect instanceof Effect.StageChanged)
                .toList();

        int demotion = -1;
        int promotion = -1;
        for (int i = 0; i < promotions.size(); i++) {
            Effect.StageChanged change = (Effect.StageChanged) promotions.get(i);
            if (change.from() == Stage.PRODUCTION && change.to() == Stage.ARCHIVED) {
                demotion = i;
            }
            if (change.ref().equals(Ref.of("fraud", "2.0.0"))
                    && change.to() == Stage.PRODUCTION) {
                promotion = i;
            }
        }
        assertTrue(demotion >= 0, "the incumbent should have been demoted");
        assertTrue(promotion >= 0, "the successor should have been promoted");
        assertTrue(demotion < promotion,
                "the demotion must arrive first, got demotion at " + demotion
                        + " and promotion at " + promotion);
    }

    @Test
    void statisticsCountWhatHappened() {
        produceEffects();
        downstream.failFrom(4);
        dispatcher.drain();
        downstream.recover();
        dispatcher.drain();

        assertEquals(kernel.view().nextEffectSeq() - 1, dispatcher.deliveredCount());
        assertEquals(1, dispatcher.failureCount());
        assertEquals(2, dispatcher.runCount());
        assertEquals(0, dispatcher.lag());
        assertNull(dispatcher.drain().failure());
    }
}
