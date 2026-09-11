package io.cairn.effects;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32C;

/**
 * An {@link AppliedLedger} in one small file, written whole every time.
 *
 * <pre>
 * "CRND" | format version | applied sequence number (8 bytes) | CRC-32C
 * </pre>
 *
 * <p>Seventeen bytes, and still written to a temporary file and moved into place atomically. That
 * is not caution for its own sake: a file smaller than a sector is not written atomically by every
 * filesystem, and a torn watermark is the worst possible thing to have on disk — it either skips
 * effects that were never applied, or replays ones that were. The checksum is what turns a torn
 * write from a plausible number into a detectable failure.
 *
 * <p>A damaged ledger throws rather than defaulting to zero. Defaulting to zero would replay every
 * effect the consumer has ever applied, which for a cache is harmless and for a collector is data
 * loss, and a library has no way to know which one it is talking to.
 */
public final class FileAppliedLedger implements AppliedLedger {

    private static final int MAGIC = 0x43524E44;
    private static final int VERSION = 1;
    private static final int SIZE = 17;

    private final Path file;
    private long applied;

    public FileAppliedLedger(Path file) {
        this.file = file;
        this.applied = read();
    }

    @Override
    public long applied() {
        return applied;
    }

    @Override
    public void record(long seq) {
        if (seq < applied) {
            throw new IllegalArgumentException(
                    "the applied watermark must not move backwards: " + applied + " -> " + seq);
        }
        if (seq == applied) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.allocate(SIZE);
        buffer.putInt(MAGIC);
        buffer.put((byte) VERSION);
        buffer.putLong(seq);
        CRC32C crc = new CRC32C();
        crc.update(buffer.array(), 0, 13);
        buffer.putInt((int) crc.getValue());

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.write(temporary, buffer.array(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, file,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot record the applied watermark in " + file, e);
        }
        applied = seq;
    }

    @Override
    public void close() {
        // Nothing held open: every write opens and closes its own file.
    }

    private long read() {
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        byte[] content;
        try {
            content = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the applied watermark from " + file, e);
        }
        if (content.length != SIZE) {
            throw new IllegalStateException(
                    file + " is " + content.length + " bytes, not " + SIZE
                            + "; the applied watermark is damaged");
        }
        ByteBuffer buffer = ByteBuffer.wrap(content);
        if (buffer.getInt() != MAGIC) {
            throw new IllegalStateException(file + " is not a cairn applied-watermark file");
        }
        int version = buffer.get();
        if (version != VERSION) {
            throw new IllegalStateException(
                    file + " has watermark format version " + version + ", not " + VERSION);
        }
        long seq = buffer.getLong();
        CRC32C crc = new CRC32C();
        crc.update(content, 0, 13);
        if (buffer.getInt() != (int) crc.getValue()) {
            throw new IllegalStateException(
                    file + " failed its checksum; the applied watermark is damaged and defaulting"
                            + " to zero would replay every effect ever applied");
        }
        return seq;
    }
}
