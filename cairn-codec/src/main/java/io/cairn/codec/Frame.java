package io.cairn.codec;

import java.util.zip.CRC32C;

/**
 * A self-describing envelope: {@code magic | format version | payload | CRC-32C}.
 *
 * <p>Each of the four parts answers a failure that has to be distinguishable from the others. The
 * magic separates "this is not a cairn file" from "this is a cairn file that is damaged", which is
 * the difference between a configuration mistake and a data loss incident. The version separates
 * "written by a newer cairn" from "corrupt"; refusing a version from the future is the only safe
 * response, because reading it optimistically means silently dropping fields. The checksum catches
 * damage that a length check cannot see, and CRC-32C rather than CRC-32 because it has a hardware
 * instruction on every CPU this runs on. And the payload is length-implied rather than
 * length-prefixed, because whatever hands the frame over already knows how long it is.
 *
 * <p>The checksum covers the magic and the version too. A frame whose header was corrupted into a
 * <i>valid-looking</i> different header is then still caught, which a checksum over the payload
 * alone would miss.
 */
final class Frame {

    /** A serialized {@link io.cairn.core.Registry}. */
    static final int MAGIC_SNAPSHOT = 0x43524E53; // "CRNS"

    /** A command log file header. */
    static final int MAGIC_LOG = 0x43524E4C; // "CRNL"

    /** A durable record of what a consumer has applied. */
    static final int MAGIC_LEDGER = 0x43524E44; // "CRND"

    /**
     * The only format version this build writes, and the highest it reads.
     *
     * <p>Bumping this is a deliberate act with a migration note in {@code CHANGELOG.md}; there is
     * no forward compatibility to lean on, because a registry that half-reads a file it does not
     * understand is worse than one that refuses to start.
     */
    static final int FORMAT_VERSION = 1;

    private Frame() {}

    /** Wraps a payload. */
    static byte[] wrap(int magic, byte[] payload) {
        ByteWriter out = new ByteWriter(payload.length + 9);
        out.u32(magic);
        out.u8(FORMAT_VERSION);
        out.bytes(payload);
        out.u32(checksum(out.array(), 0, out.length()));
        return out.toByteArray();
    }

    /**
     * Verifies a frame and returns a reader positioned at its payload.
     *
     * @throws CodecException if the magic, version or checksum is wrong, or the frame is too short
     */
    static ByteReader unwrap(int magic, byte[] framed) {
        if (framed.length < 9) {
            throw new CodecException(
                    "frame of " + framed.length + " bytes is shorter than the 9 byte envelope");
        }
        ByteReader header = new ByteReader(framed);
        int actualMagic = header.u32();
        if (actualMagic != magic) {
            throw new CodecException(
                    "expected magic " + describe(magic) + ", got " + describe(actualMagic));
        }
        int version = header.u8();
        if (version != FORMAT_VERSION) {
            throw new CodecException(
                    "format version " + version + " is not readable by this build, which writes "
                            + FORMAT_VERSION);
        }

        int payloadEnd = framed.length - 4;
        int expected = checksum(framed, 0, payloadEnd);
        int stored = new ByteReader(framed, payloadEnd, framed.length).u32();
        if (expected != stored) {
            throw new CodecException(
                    "checksum mismatch: computed " + Integer.toHexString(expected) + ", stored "
                            + Integer.toHexString(stored));
        }
        return new ByteReader(framed, 5, payloadEnd);
    }

    /** CRC-32C over a range, as an int. Exposed so the log can checksum its own records. */
    static int checksum(byte[] data, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(data, offset, length);
        return (int) crc.getValue();
    }

    private static String describe(int magic) {
        StringBuilder text = new StringBuilder(6).append('\'');
        for (int shift = 24; shift >= 0; shift -= 8) {
            int c = (magic >>> shift) & 0xFF;
            text.append(c >= 0x20 && c < 0x7F ? (char) c : '?');
        }
        return text.append('\'').toString();
    }
}
