package io.cairn.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cairn.core.Command;
import io.cairn.core.ModelId;
import io.cairn.core.Registry;
import io.cairn.core.SequencedEffect;
import io.cairn.core.Stage;
import io.cairn.core.VersionId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Everything the codec writes, it reads back identically. */
class CodecRoundTripTest {

    static java.util.List<Command> commands() {
        return CodecFixtures.everyCommand();
    }

    static java.util.List<SequencedEffect> effects() {
        return CodecFixtures.everyEffect();
    }

    @ParameterizedTest
    @MethodSource("commands")
    void everyCommandRoundTrips(Command command) {
        byte[] encoded = Codec.encodeCommand(command);
        assertEquals(command, Codec.decodeCommand(encoded));
        assertArrayEquals(encoded, Codec.encodeCommand(Codec.decodeCommand(encoded)),
                "re-encoding a decoded command must produce the same bytes");
    }

    @ParameterizedTest
    @MethodSource("effects")
    void everyEffectRoundTrips(SequencedEffect effect) {
        byte[] encoded = Codec.encodeEffect(effect);
        assertEquals(effect, Codec.decodeEffect(encoded));
        assertArrayEquals(encoded, Codec.encodeEffect(Codec.decodeEffect(encoded)));
    }

    @Test
    void aPopulatedRegistryRoundTrips() {
        Registry before = CodecFixtures.populated();
        Registry after = Codec.decodeSnapshot(Codec.encodeSnapshot(before));

        assertEquals(before, after);
        assertEquals(Codec.stateDigestHex(before), Codec.stateDigestHex(after));
    }

    @Test
    void theFixtureItselfCoversTheStatesWorthEncoding() {
        // A round-trip test proves nothing about a field the fixture never sets. This asserts the
        // fixture is as interesting as it claims, so widening the format without widening the
        // fixture fails here rather than passing quietly.
        Registry state = CodecFixtures.populated();

        assertEquals(3, state.models().size(), "three models");
        assertTrue(state.version(io.cairn.core.Ref.of("churn", "0.9.0")).orElseThrow().deleted(),
                "a tombstone");
        assertFalse(state.blob(CodecFixtures.digest(3)).orElseThrow().present(),
                "a collected artifact");
        assertEquals(2, state.blob(CodecFixtures.digest(2)).orElseThrow().refCount(),
                "an artifact shared by two versions");
        assertEquals(Stage.PRODUCTION,
                state.version(io.cairn.core.Ref.of("fraud", "1.0.0")).orElseThrow().stage(),
                "a production version");
        assertFalse(state.version(io.cairn.core.Ref.of("fraud", "1.0.0")).orElseThrow()
                .parents().isEmpty(), "cross-model lineage");
        assertFalse(state.version(io.cairn.core.Ref.of("fraud", "1.0.0")).orElseThrow()
                .labels().isEmpty(), "labels");
        assertEquals(3, state.dispatchedThrough(), "a partially acknowledged outbox");
        assertFalse(state.outbox().isEmpty(), "with something still in it");
    }

    @Test
    void anEmptyRegistryRoundTrips() {
        Registry empty = Registry.empty();
        assertEquals(empty, Codec.decodeSnapshot(Codec.encodeSnapshot(empty)));
    }

    @Test
    void theUnframedFormRoundTripsToo() {
        Registry before = CodecFixtures.populated();
        assertEquals(before, Codec.decodeState(Codec.encodeState(before)));
    }

    @Test
    void aStateDigestIsStableAcrossReconstruction() {
        Registry original = CodecFixtures.populated();
        // Rebuilt from scratch by replaying the same script: a different object graph, the same
        // state. The digest is what says so.
        Registry rebuilt = CodecFixtures.populated();

        assertEquals(Codec.stateDigestHex(original), Codec.stateDigestHex(rebuilt));
        assertEquals(64, Codec.stateDigestHex(original).length());
        assertEquals(12, Codec.stateFingerprint(original).length());
    }

    @Test
    void aDifferentStateHasADifferentDigest() {
        Registry before = CodecFixtures.populated();
        Registry after = io.cairn.core.Kernel.apply(
                        before,
                        before.appliedIndex() + 1,
                        new Command.Promote(
                                io.cairn.core.Ref.of("fraud", "1.0.1"), Stage.PRODUCTION,
                                "bob", 9_000L))
                .state();

        assertFalse(Codec.stateDigestHex(before).equals(Codec.stateDigestHex(after)));
        assertEquals(VersionId.of("1.0.1"),
                after.model(ModelId.of("fraud")).orElseThrow().production().orElseThrow().version());
    }

    @Test
    void aSnapshotIsSmallerThanTheTextItReplaces() {
        // Not a benchmark, a sanity check with a number in it: the binary form of the fixture is
        // recorded here so a change that doubles it is visible in a diff.
        Registry state = CodecFixtures.populated();
        int size = Codec.encodeSnapshot(state).length;
        assertTrue(size > 0 && size < 4096,
                "the fixture snapshot is " + size + " bytes, which is outside the expected range");
    }
}
