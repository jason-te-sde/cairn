package io.cairn.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.cairn.core.Command;
import io.cairn.core.Ref;
import io.cairn.core.Registry;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The rejection corpus.
 *
 * <p>A decoder is judged on what it refuses, not on what it accepts. Round-trip tests only ever
 * feed it bytes it wrote itself, which is the input it is least likely to get wrong; everything
 * here is input it did not write.
 *
 * <p>Two of these are exhaustive rather than illustrative — every single-bit flip and every
 * truncation of a real snapshot — because "we thought of the corruptions worth testing" is a
 * weaker claim than "all of them in this class are covered".
 */
class CodecRejectionTest {

    private static byte[] snapshot() {
        return Codec.encodeSnapshot(CodecFixtures.populated());
    }

    @Test
    void everySingleBitFlipIsCaught() {
        byte[] original = snapshot();
        int checked = 0;
        for (int index = 0; index < original.length; index++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] corrupted = original.clone();
                corrupted[index] ^= (byte) (1 << bit);
                try {
                    Codec.decodeSnapshot(corrupted);
                    fail("a flip of bit " + bit + " at byte " + index + " of " + original.length
                            + " decoded successfully");
                } catch (CodecException expected) {
                    checked++;
                }
            }
        }
        assertEquals(original.length * 8, checked,
                "the sweep must have visited every bit of the snapshot");
        assertTrue(checked > 1000, "the fixture is too small to be a meaningful sweep: " + checked);
    }

    @Test
    void everyTruncationIsCaught() {
        byte[] original = snapshot();
        for (int length = 0; length < original.length; length++) {
            byte[] truncated = Arrays.copyOf(original, length);
            int at = length;
            assertThrows(CodecException.class, () -> Codec.decodeSnapshot(truncated),
                    "a snapshot truncated to " + at + " of " + original.length
                            + " bytes decoded successfully");
        }
        // And the untruncated one still works, so the sweep above was not passing vacuously.
        assertEquals(CodecFixtures.populated(), Codec.decodeSnapshot(original));
    }

    @Test
    void trailingBytesAreRefused() {
        byte[] extended = Arrays.copyOf(snapshot(), snapshot().length + 1);
        assertThrows(CodecException.class, () -> Codec.decodeSnapshot(extended));

        byte[] command = Codec.encodeCommand(new Command.AckEffects(7));
        byte[] withTrailer = Arrays.copyOf(command, command.length + 1);
        CodecException refused =
                assertThrows(CodecException.class, () -> Codec.decodeCommand(withTrailer));
        assertTrue(refused.getMessage().contains("trailing"), refused.getMessage());
    }

    @Test
    void theWrongKindOfFileIsDistinguishableFromADamagedOne() {
        byte[] framed = snapshot();
        byte[] wrongMagic = framed.clone();
        wrongMagic[0] = 'X';
        // The checksum covers the header, so this trips the magic check first. That ordering is the
        // point: "not a cairn snapshot" and "a damaged cairn snapshot" are different incidents.
        CodecException refused =
                assertThrows(CodecException.class, () -> Codec.decodeSnapshot(wrongMagic));
        assertTrue(refused.getMessage().contains("magic"), refused.getMessage());
    }

    @Test
    void aFormatVersionFromTheFutureIsRefusedRatherThanGuessedAt() {
        byte[] framed = snapshot();
        framed[4] = 99;
        CodecException refused =
                assertThrows(CodecException.class, () -> Codec.decodeSnapshot(framed));
        assertTrue(refused.getMessage().contains("format version 99"), refused.getMessage());
    }

    @Test
    void aFrameShorterThanItsEnvelopeIsRefused() {
        assertThrows(CodecException.class, () -> Codec.decodeSnapshot(new byte[0]));
        assertThrows(CodecException.class, () -> Codec.decodeSnapshot(new byte[8]));
    }

    @Test
    void aVarintThatCannotFitIn64BitsIsRefused() {
        byte[] tenContinuations = new byte[11];
        Arrays.fill(tenContinuations, (byte) 0x80);
        tenContinuations[10] = 0x01;
        ByteReader in = new ByteReader(tenContinuations);
        CodecException refused = assertThrows(CodecException.class, in::uvarint);
        assertTrue(refused.getMessage().contains("varint"), refused.getMessage());
    }

    @Test
    void aVarintWhoseTenthByteCarriesMoreThanOneBitIsRefused() {
        byte[] overflowing = new byte[10];
        Arrays.fill(overflowing, (byte) 0x80);
        overflowing[9] = 0x02;
        ByteReader in = new ByteReader(overflowing);
        assertThrows(CodecException.class, in::uvarint);
    }

    @Test
    void aBooleanOutsideZeroAndOneIsRefused() {
        ByteReader in = new ByteReader(new byte[] {2});
        CodecException refused = assertThrows(CodecException.class, in::bool);
        assertTrue(refused.getMessage().contains("must be 0 or 1"), refused.getMessage());
    }

    @Test
    void malformedUtf8IsRefusedRatherThanSubstituted() {
        // The JDK's default is to replace a bad sequence with U+FFFD, which would turn a corrupt
        // name into a valid different one and let it into the registry.
        ByteWriter out = new ByteWriter();
        out.uvarint(2);
        out.bytes(new byte[] {(byte) 0xFF, (byte) 0xFE});
        ByteReader in = new ByteReader(out.toByteArray());

        CodecException refused = assertThrows(CodecException.class, () -> in.string(64));
        assertTrue(refused.getMessage().contains("UTF-8"), refused.getMessage());
    }

    @Test
    void aStringLongerThanItsLimitIsRefusedBeforeItIsAllocated() {
        ByteWriter out = new ByteWriter();
        out.uvarint(1_000_000);
        ByteReader in = new ByteReader(out.toByteArray());
        CodecException refused = assertThrows(CodecException.class, () -> in.string(64));
        assertTrue(refused.getMessage().contains("exceeds"), refused.getMessage());
    }

    @Test
    void anIdentifierThatWouldBeIllegalIsRefusedOnDecode() {
        // Nothing this codec writes can hold a colon, because the core refuses to construct such a
        // name. A file that holds one anyway is corrupt or forged, and the decoder has to be the
        // one to say so, because it is the only thing that reads the file.
        ByteWriter out = new ByteWriter();
        out.u8(5 + 1);
        ByteWriter command = new ByteWriter();
        command.u8(3);
        command.string("fraud:v2");
        command.string("1.0.0");

        CodecException refused = assertThrows(
                CodecException.class, () -> Codec.decodeCommand(command.toByteArray()));
        assertTrue(refused.getMessage().contains("invalid model id"), refused.getMessage());
        assertTrue(out.length() > 0);
    }

    @Test
    void unknownTagsAreRefused() {
        assertThrows(CodecException.class, () -> Codec.decodeCommand(new byte[] {99}));
        assertThrows(CodecException.class, () -> Codec.decodeEffect(new byte[] {1, 99}));

        ByteWriter badStage = new ByteWriter();
        badStage.u8(3);
        Values.ref(badStage, Ref.of("m", "1.0.0"));
        badStage.u8(9);
        CodecException refused = assertThrows(
                CodecException.class, () -> Codec.decodeCommand(badStage.toByteArray()));
        assertTrue(refused.getMessage().contains("stage tag"), refused.getMessage());

        ByteWriter badDigest = new ByteWriter();
        badDigest.u8(1);
        badDigest.u8(9);
        assertThrows(CodecException.class, () -> Codec.decodeCommand(badDigest.toByteArray()));
    }

    @Test
    void aCountLargerThanTheRemainingInputIsRefusedBeforeAllocating() {
        ByteWriter out = new ByteWriter();
        out.uvarint(0);
        out.uvarint(1);
        out.uvarint(0);
        out.uvarint(4_000_000_000L);

        CodecException refused = assertThrows(
                CodecException.class, () -> Codec.decodeState(out.toByteArray()));
        assertTrue(refused.getMessage().contains("bytes remain"), refused.getMessage());
    }

    @Test
    void anOutboxThatDisagreesWithTheWatermarkIsRefused() {
        // dispatchedThrough=0 and nextEffectSeq=3 implies two effects in the outbox. This says one,
        // which is a state the kernel cannot produce, so a file holding it is corrupt.
        ByteWriter out = new ByteWriter();
        out.uvarint(0);
        out.uvarint(3);
        out.uvarint(0);
        out.uvarint(0);
        out.uvarint(0);
        out.uvarint(1);
        out.bytes(Codec.encodeEffect(CodecFixtures.everyEffect().get(0)));

        CodecException refused = assertThrows(
                CodecException.class, () -> Codec.decodeState(out.toByteArray()));
        assertTrue(refused.getMessage().contains("watermark"), refused.getMessage());
    }

    @Test
    void aRegistryTheKernelCouldNotProduceIsRefused() {
        // A blob marked absent while a version still references it: the one cross-field invariant
        // the value types enforce themselves. It has to be caught on decode too, because a file is
        // not built by the constructors that guard the live path.
        ByteWriter out = new ByteWriter();
        out.uvarint(0);
        out.uvarint(1);
        out.uvarint(0);
        out.uvarint(0);
        out.uvarint(1);
        Values.digest(out, CodecFixtures.digest(1));
        out.uvarint(10);
        out.uvarint(1);
        out.bool(false);
        out.uvarint(0);
        out.uvarint(0);

        CodecException refused = assertThrows(
                CodecException.class, () -> Codec.decodeState(out.toByteArray()));
        assertTrue(refused.getMessage().contains("still referenced"), refused.getMessage());
    }

    @Test
    void aNegativeSizeCannotBeEncodedAtAll() {
        ByteWriter out = new ByteWriter();
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> out.uvarint(-1));
        assertTrue(refused.getMessage().contains("must not be negative"), refused.getMessage());
    }

    @Test
    void aValidSnapshotStillDecodesAfterAllOfThat() {
        Registry state = CodecFixtures.populated();
        assertEquals(state, Codec.decodeSnapshot(Codec.encodeSnapshot(state)));
    }
}
