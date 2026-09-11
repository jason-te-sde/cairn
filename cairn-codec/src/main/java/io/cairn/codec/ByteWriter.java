package io.cairn.codec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Writes the primitives the encoding is built from.
 *
 * <p>Two integer encodings, and the difference matters. {@code uvarint} is unsigned LEB128 and
 * refuses a negative value, which is the right shape for a length, a count or a sequence number:
 * the refusal turns "somebody passed -1" into a failure at the call site rather than a nine-byte
 * field on disk. {@code svarint} is zigzag, for the one kind of field that is legitimately signed —
 * a caller-supplied millisecond timestamp, which may predate 1970 and is not the codec's business
 * to police.
 */
final class ByteWriter {

    private byte[] buffer;
    private int length;

    ByteWriter() {
        this(64);
    }

    ByteWriter(int initialCapacity) {
        buffer = new byte[Math.max(16, initialCapacity)];
    }

    void u8(int value) {
        ensure(1);
        buffer[length++] = (byte) value;
    }

    void u32(int value) {
        ensure(4);
        buffer[length++] = (byte) (value >>> 24);
        buffer[length++] = (byte) (value >>> 16);
        buffer[length++] = (byte) (value >>> 8);
        buffer[length++] = (byte) value;
    }

    /** Unsigned LEB128. Rejects a negative value rather than encoding it as ten bytes. */
    void uvarint(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("uvarint must not be negative: " + value);
        }
        bits(value);
    }

    /**
     * Zigzag LEB128, so a small negative number is a small number of bytes.
     *
     * <p>Goes through {@link #bits} rather than {@link #uvarint} because zigzagging a value of
     * large magnitude sets the top bit: {@code Long.MIN_VALUE} zigzags to a bit pattern that reads
     * as -1 when interpreted as a signed long, and it is the correct encoding. The check in
     * {@code uvarint} exists to catch a caller passing a negative length, which is a different
     * thing entirely, so the two paths must not share it. Found by {@code VarintTest}'s boundary
     * sweep, which is the only reason this comment exists rather than a corrupt timestamp.
     */
    void svarint(long value) {
        bits((value << 1) ^ (value >> 63));
    }

    /** LEB128 over the 64 bits as they are, with no opinion about what they mean. */
    private void bits(long value) {
        ensure(10);
        long remaining = value;
        while ((remaining & ~0x7FL) != 0) {
            buffer[length++] = (byte) ((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        buffer[length++] = (byte) remaining;
    }

    /**
     * A UTF-8 string with a byte-length prefix.
     *
     * <p>Length-prefixed, never delimited. This one line is the whole answer to the format this
     * project replaces: there is no character a value could contain that would change how it is
     * read back, because the reader is told how many bytes to take before it takes them.
     */
    void string(String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        uvarint(utf8.length);
        bytes(utf8);
    }

    void bytes(byte[] value) {
        ensure(value.length);
        System.arraycopy(value, 0, buffer, length, value.length);
        length += value.length;
    }

    void bool(boolean value) {
        u8(value ? 1 : 0);
    }

    int length() {
        return length;
    }

    byte[] toByteArray() {
        return Arrays.copyOf(buffer, length);
    }

    /** The backing array, for a checksum that must not copy. Valid up to {@link #length()}. */
    byte[] array() {
        return buffer;
    }

    private void ensure(int extra) {
        if (length + extra <= buffer.length) {
            return;
        }
        int capacity = buffer.length;
        while (capacity < length + extra) {
            capacity = capacity + (capacity >> 1) + 16;
        }
        buffer = Arrays.copyOf(buffer, capacity);
    }
}
