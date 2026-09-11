package io.cairn.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cairn.core.Effect;
import io.cairn.core.Ref;
import io.cairn.core.SequencedEffect;
import org.junit.jupiter.api.Test;

/** The at-most-once half, on its own. */
class IdempotentSinkTest {

    private static SequencedEffect effect(long seq) {
        return new SequencedEffect(
                seq, new Effect.VersionDeleted(Ref.of("fraud", "1.0.0"), "alice", seq));
    }

    @Test
    void anEffectAtOrBelowTheWatermarkIsDropped() {
        AppliedLedger ledger = new InMemoryAppliedLedger();
        ledger.record(5);
        RecordingSink downstream = new RecordingSink();
        IdempotentSink sink = new IdempotentSink(downstream, ledger);

        sink.deliver(effect(3));
        sink.deliver(effect(5));
        sink.deliver(effect(6));

        assertEquals(1, downstream.received().size());
        assertEquals(6, downstream.received().get(0).seq());
        assertEquals(2, sink.duplicateCount());
        assertEquals(1, sink.appliedCount());
        assertEquals(6, sink.watermark());
    }

    @Test
    void theWatermarkMovesOnlyAfterTheDeliveryLands() {
        // Recording first would turn a crash between the two into a skipped effect, and an effect
        // that orders an artifact deleted is not something to skip.
        AppliedLedger ledger = new InMemoryAppliedLedger();
        RecordingSink downstream = new RecordingSink();
        downstream.failFrom(1);
        IdempotentSink sink = new IdempotentSink(downstream, ledger);

        assertThrows(IllegalStateException.class, () -> sink.deliver(effect(1)));

        assertEquals(0, ledger.applied(), "a refused delivery must not advance the watermark");
        assertEquals(0, sink.appliedCount());
    }

    @Test
    void theWatermarkNeverMovesBackwards() {
        AppliedLedger ledger = new InMemoryAppliedLedger();
        ledger.record(10);
        assertThrows(IllegalArgumentException.class, () -> ledger.record(9));
        assertEquals(10, ledger.applied());
    }

    @Test
    void theNameSaysWhatIsWrappingWhat() {
        IdempotentSink sink =
                new IdempotentSink(new RecordingSink(), new InMemoryAppliedLedger());
        assertTrue(sink.name().contains("recording"), sink.name());
        assertTrue(sink.name().contains("idempotent"), sink.name());
    }
}
