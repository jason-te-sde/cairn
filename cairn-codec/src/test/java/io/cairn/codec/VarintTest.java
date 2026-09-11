package io.cairn.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The two integer encodings, at their boundaries.
 *
 * <p>Varints are the part of a codec most likely to be subtly wrong, and the wrongness shows up at
 * the byte-length boundaries — 127 to 128, 16383 to 16384 — rather than on the values a
 * hand-written test tends to pick.
 */
class VarintTest {

    static List<Long> unsignedBoundaries() {
        List<Long> values = new ArrayList<>(List.of(0L, 1L, 2L, 63L, 64L));
        // Stops at 56, not 63: 1L << 63 is Long.MIN_VALUE, and uvarint refuses a negative on
        // purpose — a negative length is a caller bug and the whole point of the check is that it
        // fails at the call site. Long.MAX_VALUE covers the nine-byte case.
        for (int shift = 7; shift <= 56; shift += 7) {
            long boundary = 1L << shift;
            values.add(boundary - 1);
            values.add(boundary);
            values.add(boundary + 1);
        }
        values.add(Long.MAX_VALUE);
        values.add(Long.MAX_VALUE - 1);
        return values;
    }

    static List<Long> signedBoundaries() {
        List<Long> values = new ArrayList<>();
        for (long value : unsignedBoundaries()) {
            values.add(value);
            values.add(-value);
        }
        // The two values that broke svarint before it was fixed: zigzagging either one sets the
        // top bit of the result, which the unsigned writer refused until the two paths were
        // separated.
        values.add(Long.MIN_VALUE);
        values.add(Long.MAX_VALUE);
        return values;
    }

    @ParameterizedTest
    @MethodSource("unsignedBoundaries")
    void unsignedValuesRoundTrip(long value) {
        ByteWriter out = new ByteWriter();
        out.uvarint(value);
        ByteReader in = new ByteReader(out.toByteArray());
        assertEquals(value, in.uvarint());
        in.end();
    }

    @ParameterizedTest
    @MethodSource("signedBoundaries")
    void signedValuesRoundTrip(long value) {
        ByteWriter out = new ByteWriter();
        out.svarint(value);
        ByteReader in = new ByteReader(out.toByteArray());
        assertEquals(value, in.svarint());
        in.end();
    }

    @Test
    void aNegativeValueIsRefusedByTheUnsignedWriterAndAcceptedByTheSignedOne() {
        ByteWriter out = new ByteWriter();
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> out.uvarint(-1));

        ByteWriter signed = new ByteWriter();
        signed.svarint(Long.MIN_VALUE);
        assertEquals(Long.MIN_VALUE, new ByteReader(signed.toByteArray()).svarint());
    }

    @Test
    void aSmallNegativeNumberIsSmall() {
        // The reason timestamps use zigzag rather than two's complement: without it, -1 is ten
        // bytes, and a field that is usually a small number becomes the largest field in a record.
        ByteWriter zigzag = new ByteWriter();
        zigzag.svarint(-1);
        assertEquals(1, zigzag.length());

        ByteWriter large = new ByteWriter();
        large.svarint(1_700_000_000_000L);
        assertTrue(large.length() <= 6, "a millisecond timestamp is " + large.length() + " bytes");
    }

    @Test
    void theEncodingIsTheShortestOneThatFits() {
        for (int shift = 0; shift <= 63; shift += 7) {
            ByteWriter out = new ByteWriter();
            out.uvarint((1L << shift) - 1);
            int expected = Math.max(1, (shift + 6) / 7);
            assertEquals(expected, out.length(),
                    "2^" + shift + "-1 should encode in " + expected + " bytes");
        }
    }

    @Test
    void manyRandomValuesRoundTripTogetherInOneBuffer() {
        // A single value round-tripping proves the encoding; a run of them in one buffer proves the
        // reader consumes exactly what the writer produced, which is the failure that shows up as a
        // corrupt field three records later.
        Random random = new Random(20260911L);
        List<Long> values = new ArrayList<>();
        ByteWriter out = new ByteWriter();
        for (int i = 0; i < 2000; i++) {
            // >>> at least one bit, so the value stays non-negative: this exercises the length
            // boundaries, not the negativity check, which has its own test.
            long value = random.nextLong() >>> (1 + random.nextInt(63));
            values.add(value);
            out.uvarint(value);
        }
        ByteReader in = new ByteReader(out.toByteArray());
        for (long expected : values) {
            assertEquals(expected, in.uvarint());
        }
        in.end();
    }

    @Test
    void aStringsLengthIsCountedInBytesNotCharacters() {
        // The bug this guards: bounding a UTF-8 length by a character count. A three-byte character
        // then passes a byte-length check it should fail, or fails one it should pass.
        String multiByte = "中".repeat(10);
        ByteWriter out = new ByteWriter();
        out.string(multiByte);
        assertEquals(31, out.length(), "30 bytes of UTF-8 plus a one-byte length");

        ByteReader in = new ByteReader(out.toByteArray());
        assertEquals(multiByte, in.string(10));
        in.end();
    }
}
