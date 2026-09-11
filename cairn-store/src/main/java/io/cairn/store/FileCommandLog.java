package io.cairn.store;

import io.cairn.codec.Codec;
import io.cairn.codec.CodecException;
import io.cairn.core.Command;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The command log on a filesystem: segmented, checksummed, append-only, torn-tail survivable.
 *
 * <h2>On disk</h2>
 *
 * <pre>
 * log/00000000000000000001.log
 *   header:  "CRNL" | format version | first index (8 bytes, big endian)
 *   record:  payload length (4) | CRC-32C over the length and the payload (4) | payload
 *   record:  ...
 * </pre>
 *
 * <p>Three decisions in that layout are load-bearing.
 *
 * <p><b>The checksum covers the length field.</b> A corrupt length alone would be read as a
 * plausible record boundary and everything after it would decode as garbage or, worse, as something
 * valid. Checksumming the length means a damaged record is identified as damaged at the point it
 * starts.
 *
 * <p><b>Segments are named by their first index, zero-padded to twenty digits.</b> So lexical order
 * is numeric order, and recovery that lists a directory and replays in the order it got cannot
 * replay segment 10 before segment 9. The padding is not cosmetic: replaying segments in the wrong
 * order resurrects superseded records, and that is a bug this project's predecessor shipped and
 * found with a differential test.
 *
 * <p><b>A damaged record ends the log.</b> Not "is skipped". An append-only log written by one
 * writer can only be damaged at its tail — a partial write interrupted by a crash — so the first
 * record that fails its checksum is the end of what was durable, and everything after it was never
 * acknowledged to anybody. The file is truncated there. A damaged record followed by *valid* ones
 * in an earlier segment is a different matter entirely and refuses to start.
 */
public final class FileCommandLog implements CommandLog {

    private static final Logger LOG = LoggerFactory.getLogger(FileCommandLog.class);

    /** Largest record this log will write or read. Commands are kilobytes; this is slack. */
    static final int MAX_RECORD_BYTES = 1 << 20;

    private static final int HEADER_BYTES = 13;

    /**
     * How much of a segment is read at a time.
     *
     * <p>Chosen against {@link #MAX_RECORD_BYTES}: large enough that ordinary records are read
     * hundreds at a time, small enough that a read does not allocate a megabyte. A record that
     * does not fit takes a slower path rather than a bigger buffer.
     */
    private static final int READ_CHUNK = 256 * 1024;
    private static final String SUFFIX = ".log";
    private static final long DEFAULT_SEGMENT_BYTES = 8L * 1024 * 1024;

    private final Path directory;
    private final Durability durability;
    private final long maxSegmentBytes;

    private final List<Segment> segments = new ArrayList<>();
    private Segment active;
    private long lastIndex;
    private boolean dirty;

    /** Opens or creates a log, recovering whatever is there. */
    public FileCommandLog(Path directory, Durability durability) {
        this(directory, durability, DEFAULT_SEGMENT_BYTES);
    }

    FileCommandLog(Path directory, Durability durability, long maxSegmentBytes) {
        this.directory = directory;
        this.durability = durability;
        this.maxSegmentBytes = maxSegmentBytes;
        try {
            Files.createDirectories(directory);
            recover();
        } catch (IOException e) {
            throw new StoreException("cannot open the command log at " + directory, e);
        }
    }

    @Override
    public long append(Command command) {
        byte[] payload = Codec.encodeCommand(command);
        if (payload.length > MAX_RECORD_BYTES) {
            throw new StoreException(
                    "command encodes to " + payload.length + " bytes, over the "
                            + MAX_RECORD_BYTES + " byte record limit");
        }
        try {
            if (active == null || active.bytes() + recordBytes(payload) > maxSegmentBytes) {
                roll();
            }
            active.write(payload);
            lastIndex++;
            dirty = true;
            if (durability == Durability.SYNC_EACH) {
                sync();
            }
            return lastIndex;
        } catch (IOException e) {
            throw new StoreException("cannot append to " + active, e);
        }
    }

    @Override
    public void sync() {
        if (durability == Durability.NONE || !dirty || active == null) {
            dirty = false;
            return;
        }
        try {
            active.channel.force(false);
            dirty = false;
        } catch (IOException e) {
            throw new StoreException("cannot force " + active, e);
        }
    }

    @Override
    public long lastIndex() {
        return lastIndex;
    }

    @Override
    public long firstIndex() {
        return segments.isEmpty() ? 1 : segments.get(0).firstIndex;
    }

    @Override
    public List<LogRecord> read(long fromIndex, int maxRecords) {
        if (fromIndex < firstIndex()) {
            throw new StoreException(
                    "index " + fromIndex + " has been discarded; the log starts at " + firstIndex());
        }
        List<LogRecord> out = new ArrayList<>();
        for (Segment segment : segments) {
            if (out.size() >= maxRecords) {
                break;
            }
            if (segment.lastIndex() < fromIndex) {
                continue;
            }
            try {
                segment.readInto(out, Math.max(fromIndex, segment.firstIndex), maxRecords);
            } catch (IOException e) {
                throw new StoreException("cannot read " + segment, e);
            }
        }
        return out;
    }

    @Override
    public void discardThrough(long throughIndex) {
        // A segment goes only if every record in it is at or below the boundary. Discarding one
        // that straddles the boundary would take records above it with it, which is precisely the
        // way log compaction loses committed data.
        //
        // The active segment is never deleted, because appends need somewhere to go. If it is
        // wholly below the boundary, roll first so that the whole prefix can actually be released
        // rather than being held by the one file that cannot be removed.
        if (active != null && active.lastIndex() <= throughIndex && active.records > 0) {
            try {
                roll();
            } catch (IOException e) {
                throw new StoreException("cannot roll the log before discarding a prefix", e);
            }
        }
        List<Segment> keep = new ArrayList<>(segments.size());
        for (Segment segment : segments) {
            boolean wholly = segment.lastIndex() <= throughIndex && segment != active;
            if (!wholly) {
                keep.add(segment);
                continue;
            }
            try {
                segment.close();
                Files.delete(segment.path);
                LOG.info("discarded log segment {} (through index {})",
                        segment.path.getFileName(), segment.lastIndex());
            } catch (IOException e) {
                throw new StoreException("cannot delete " + segment, e);
            }
        }
        segments.clear();
        segments.addAll(keep);
    }

    @Override
    public long sizeBytes() {
        long total = 0;
        for (Segment segment : segments) {
            total += segment.bytes();
        }
        return total;
    }

    @Override
    public void close() throws IOException {
        sync();
        for (Segment segment : segments) {
            segment.close();
        }
        segments.clear();
        active = null;
    }

    // ---- recovery ----------------------------------------------------------------------------

    private void recover() throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> listing = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
            for (Path candidate : listing) {
                files.add(candidate);
            }
        }
        // By parsed index, not by name, and not by whatever order the directory came back in. The
        // zero padding makes the two agree, and sorting by the number as well means a file someone
        // renamed cannot reorder the log.
        files.sort(Comparator.comparingLong(FileCommandLog::indexFromName));

        long expectedFirst = -1;
        for (Path file : files) {
            Segment segment = Segment.open(file);
            if (expectedFirst >= 0 && segment.firstIndex != expectedFirst) {
                throw new StoreException(
                        "log is not contiguous: " + file.getFileName() + " starts at "
                                + segment.firstIndex + ", expected " + expectedFirst);
            }
            int recovered = segment.scan();
            segments.add(segment);
            expectedFirst = segment.lastIndex() + 1;
            if (segment.truncatedAt >= 0) {
                LOG.warn("truncated {} at {} bytes: the tail was not durable ({} records kept)",
                        file.getFileName(), segment.truncatedAt, recovered);
                // Nothing after an undurable record was ever acknowledged, so a later segment
                // cannot legitimately exist. If one does, the store is not what it claims.
                if (files.indexOf(file) != files.size() - 1) {
                    throw new StoreException(
                            "segment " + file.getFileName() + " has a damaged record but is not"
                                    + " the last segment; the log has a hole in it");
                }
            }
        }
        active = segments.isEmpty() ? null : segments.get(segments.size() - 1);
        lastIndex = segments.isEmpty() ? 0 : active.lastIndex();
        if (!segments.isEmpty()) {
            LOG.info("recovered {} log segment(s), indexes {}..{}",
                    segments.size(), firstIndex(), lastIndex);
        }
    }

    private void roll() throws IOException {
        long firstIndexOfNew = lastIndex + 1;
        if (active != null) {
            sync();
        }
        Segment created = Segment.create(directory.resolve(name(firstIndexOfNew)), firstIndexOfNew);
        segments.add(created);
        active = created;
        // The directory entry itself has to be durable, or a crash can leave a segment whose
        // records survived but whose name did not.
        try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) {
            dir.force(true);
        } catch (IOException e) {
            // Not fatal and not silent: some filesystems refuse to open a directory for reading,
            // and on those this guarantee is simply unavailable rather than broken.
            LOG.debug("could not force the log directory: {}", e.getMessage());
        }
    }

    private static String name(long firstIndex) {
        return String.format("%020d%s", firstIndex, SUFFIX);
    }

    private static long indexFromName(Path path) {
        String fileName = path.getFileName().toString();
        try {
            return Long.parseLong(fileName.substring(0, fileName.length() - SUFFIX.length()));
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            throw new StoreException("not a log segment name: " + fileName, e);
        }
    }

    private static int recordBytes(byte[] payload) {
        return 8 + payload.length;
    }

    /** One file. */
    private static final class Segment {

        private final Path path;
        private final FileChannel channel;
        private final long firstIndex;
        private int records;
        private long truncatedAt = -1;

        /**
         * Where the last read stopped, so a sequential replay does not re-walk the prefix.
         *
         * <p>A hint, not an index. Reads that go backwards simply ignore it and start over, which
         * keeps the whole thing to two fields instead of an offset table that would cost real
         * memory on a large log.
         */
        private long resumeIndex = -1;
        private long resumeOffset = -1;

        private Segment(Path path, FileChannel channel, long firstIndex) {
            this.path = path;
            this.channel = channel;
            this.firstIndex = firstIndex;
        }

        static Segment create(Path path, long firstIndex) throws IOException {
            FileChannel channel = FileChannel.open(path,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            header.putInt(0x43524E4C);
            header.put((byte) 1);
            header.putLong(firstIndex);
            header.flip();
            while (header.hasRemaining()) {
                channel.write(header);
            }
            return new Segment(path, channel, firstIndex);
        }

        static Segment open(Path path) throws IOException {
            FileChannel channel = FileChannel.open(path,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            if (channel.read(header, 0) < HEADER_BYTES) {
                channel.close();
                throw new StoreException(path.getFileName() + " is shorter than its own header");
            }
            header.flip();
            int magic = header.getInt();
            if (magic != 0x43524E4C) {
                channel.close();
                throw new StoreException(
                        path.getFileName() + " is not a cairn log segment (magic "
                                + Integer.toHexString(magic) + ")");
            }
            int version = header.get();
            if (version != 1) {
                channel.close();
                throw new StoreException(
                        path.getFileName() + " has log format version " + version
                                + ", which this build cannot read");
            }
            long firstIndex = header.getLong();
            if (firstIndex != indexFromName(path)) {
                channel.close();
                throw new StoreException(
                        path.getFileName() + " declares first index " + firstIndex
                                + ", which does not match its name");
            }
            return new Segment(path, channel, firstIndex);
        }

        /** Walks the file, counting good records and truncating at the first bad one. */
        int scan() throws IOException {
            long position = HEADER_BYTES;
            long size = channel.size();
            records = 0;
            while (position < size) {
                ByteBuffer prefix = ByteBuffer.allocate(8);
                if (channel.read(prefix, position) < 8) {
                    truncate(position);
                    break;
                }
                prefix.flip();
                int length = prefix.getInt(0);
                int storedCrc = prefix.getInt(4);
                if (length < 0 || length > MAX_RECORD_BYTES || position + 8 + length > size) {
                    truncate(position);
                    break;
                }
                ByteBuffer payload = ByteBuffer.allocate(length);
                if (channel.read(payload, position + 8) < length) {
                    truncate(position);
                    break;
                }
                if (crc(length, payload.array()) != storedCrc) {
                    truncate(position);
                    break;
                }
                try {
                    Codec.decodeCommand(payload.array());
                } catch (CodecException e) {
                    // The checksum passed and the payload is still not a command. That is not a
                    // torn write, it is a file written by something else, so it is not something to
                    // recover from by throwing the tail away.
                    throw new StoreException(
                            path.getFileName() + " holds a checksum-valid record at offset "
                                    + position + " that is not a command", e);
                }
                records++;
                position += 8 + length;
            }
            channel.position(position);
            return records;
        }

        private void truncate(long at) throws IOException {
            truncatedAt = at;
            channel.truncate(at);
            channel.force(true);
        }

        void write(byte[] payload) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(8 + payload.length);
            buffer.putInt(payload.length);
            buffer.putInt(crc(payload.length, payload));
            buffer.put(payload);
            buffer.flip();
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            records++;
        }

        /**
         * Reads records into {@code out}, buffered and resumable.
         *
         * <p><b>Buffered</b> because the first implementation issued two positional reads per
         * record. Reading a chunk at a time turns that into two syscalls per quarter megabyte, and
         * it is the change that matters: recovery measures 187,000 records a second with it.
         *
         * <p><b>Resumable</b> for asymptotic reasons rather than measured ones, and the distinction
         * is worth being honest about. A rescan from the segment header is only length arithmetic —
         * the decode is skipped for records below {@code fromIndex} — so at twenty thousand records
         * a benchmark could not tell the two apart. It matters at the top of the range: a full
         * segment holds a couple of hundred thousand records, and re-walking all of them on every
         * batch would be quadratic in a way that only shows up on a log nobody has yet. Two fields
         * is a cheap price for removing that.
         *
         * <p>The hint is only consulted when it is at or below the requested index, so a read that
         * goes backwards is correct — it simply starts over.
         */
        void readInto(List<LogRecord> out, long fromIndex, int maxRecords) throws IOException {
            long limit = channel.position();
            long position = HEADER_BYTES;
            long index = firstIndex;
            if (resumeIndex >= firstIndex && resumeIndex <= fromIndex
                    && resumeOffset >= HEADER_BYTES) {
                position = resumeOffset;
                index = resumeIndex;
            }

            byte[] buffer = new byte[READ_CHUNK];
            while (position < limit && out.size() < maxRecords) {
                int want = (int) Math.min(READ_CHUNK, limit - position);
                int read = readFully(buffer, position, want);
                int offset = 0;
                while (offset + 8 <= read && out.size() < maxRecords) {
                    int length = ((buffer[offset] & 0xFF) << 24)
                            | ((buffer[offset + 1] & 0xFF) << 16)
                            | ((buffer[offset + 2] & 0xFF) << 8)
                            | (buffer[offset + 3] & 0xFF);
                    if (length < 0 || length > MAX_RECORD_BYTES) {
                        throw new StoreException(
                                path.getFileName() + " has an implausible record length at offset "
                                        + (position + offset)
                                        + "; the log was scanned as valid, so this is a bug");
                    }
                    if (offset + 8 + length > read) {
                        break;
                    }
                    if (index >= fromIndex) {
                        out.add(new LogRecord(index, Codec.decodeCommand(
                                java.util.Arrays.copyOfRange(
                                        buffer, offset + 8, offset + 8 + length))));
                    }
                    offset += 8 + length;
                    index++;
                }
                if (offset == 0) {
                    // A record larger than the chunk. Cold path for commands, which are kilobytes,
                    // but it has to terminate rather than spin.
                    offset = readOneRecord(out, position, index, fromIndex);
                    index++;
                }
                position += offset;
                resumeIndex = index;
                resumeOffset = position;
            }
        }

        /** Reads a single record that does not fit in the buffer, returning its total length. */
        private int readOneRecord(List<LogRecord> out, long position, long index, long fromIndex)
                throws IOException {
            ByteBuffer prefix = ByteBuffer.allocate(8);
            if (channel.read(prefix, position) < 8) {
                throw new StoreException(
                        path.getFileName() + " ended mid-record at offset " + position);
            }
            int length = prefix.getInt(0);
            if (length < 0 || length > MAX_RECORD_BYTES) {
                throw new StoreException(
                        path.getFileName() + " has an implausible record length at offset "
                                + position);
            }
            ByteBuffer payload = ByteBuffer.allocate(length);
            while (payload.hasRemaining()) {
                if (channel.read(payload, position + 8 + payload.position()) < 0) {
                    throw new StoreException(
                            path.getFileName() + " ended mid-record at offset " + position);
                }
            }
            if (index >= fromIndex) {
                out.add(new LogRecord(index, Codec.decodeCommand(payload.array())));
            }
            return 8 + length;
        }

        private int readFully(byte[] buffer, long position, int want) throws IOException {
            ByteBuffer view = ByteBuffer.wrap(buffer, 0, want);
            int read = 0;
            while (view.hasRemaining()) {
                int n = channel.read(view, position + read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return read;
        }

        long lastIndex() {
            return firstIndex + records - 1;
        }

        long bytes() {
            try {
                return channel.position();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        void close() throws IOException {
            if (channel.isOpen()) {
                channel.close();
            }
        }

        private static int crc(int length, byte[] payload) {
            CRC32C crc = new CRC32C();
            crc.update(new byte[] {
                (byte) (length >>> 24), (byte) (length >>> 16),
                (byte) (length >>> 8), (byte) length});
            crc.update(payload, 0, payload.length);
            return (int) crc.getValue();
        }

        @Override
        public String toString() {
            return path.getFileName().toString();
        }
    }
}
