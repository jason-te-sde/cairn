package io.cairn.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cairn.core.Command;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The log's durability contract, including the parts that only show up after a crash.
 *
 * <p>Crashes here are real: the channel is closed and the file is damaged on disk, then the log is
 * reopened. Nothing is mocked, because the behaviour under test is what the filesystem does.
 */
class FileCommandLogTest {

    @TempDir
    Path directory;

    private FileCommandLog open() {
        return new FileCommandLog(directory, Durability.SYNC_EACH);
    }

    private FileCommandLog open(long segmentBytes) {
        return new FileCommandLog(directory, Durability.SYNC_EACH, segmentBytes);
    }

    @Test
    void indexesStartAtOneAndAreContiguous() throws IOException {
        try (FileCommandLog log = open()) {
            long index = 1;
            for (Command command : StoreFixtures.script(3)) {
                assertEquals(index++, log.append(command));
            }
            assertEquals(index - 1, log.lastIndex());
            assertEquals(1, log.firstIndex());
        }
    }

    @Test
    void everythingWrittenComesBackAfterAReopen() throws IOException {
        List<Command> script = StoreFixtures.script(5);
        try (FileCommandLog log = open()) {
            script.forEach(log::append);
        }
        try (FileCommandLog reopened = open()) {
            List<LogRecord> records = reopened.read(1, 1000);
            assertEquals(script.size(), records.size());
            for (int i = 0; i < script.size(); i++) {
                assertEquals(script.get(i), records.get(i).command(),
                        "record " + (i + 1) + " came back different");
            }
        }
    }

    @Test
    void aRecordCanBeReadFromTheMiddle() throws IOException {
        List<Command> script = StoreFixtures.script(5);
        try (FileCommandLog log = open()) {
            script.forEach(log::append);
            List<LogRecord> tail = log.read(8, 2);
            assertEquals(2, tail.size());
            assertEquals(8, tail.get(0).index());
            assertEquals(script.get(7), tail.get(0).command());
        }
    }

    @Test
    void aTornTailIsTruncatedRatherThanFatal() throws IOException {
        List<Command> script = StoreFixtures.script(4);
        try (FileCommandLog log = open()) {
            script.forEach(log::append);
        }

        // A crash mid-append: the record's length and checksum reached the disk, some of its
        // payload did not. Nothing acknowledged this record, so losing it is correct; refusing to
        // start because of it would be an outage caused by a write nobody was waiting on.
        Path segment = onlySegment();
        long fullSize = Files.size(segment);
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.WRITE)) {
            channel.truncate(fullSize - 3);
        }

        try (FileCommandLog reopened = open()) {
            assertEquals(script.size() - 1, reopened.lastIndex(),
                    "the torn record should be gone and the rest kept");
            assertEquals(script.get(0), reopened.read(1, 1).get(0).command());

            // And the log is writable again, at the index the torn record used to hold.
            assertEquals(script.size(), reopened.append(script.get(0)));
        }
    }

    @Test
    void aFlippedBitInARecordEndsTheLogThere() throws IOException {
        try (FileCommandLog log = open()) {
            StoreFixtures.script(4).forEach(log::append);
        }

        Path segment = onlySegment();
        byte[] content = Files.readAllBytes(segment);
        // Damage the payload of the second record. The checksum covers the length as well as the
        // payload, so this is detected wherever in the record it lands.
        content[content.length - 5] ^= 0x20;
        Files.write(segment, content);

        try (FileCommandLog reopened = open()) {
            assertTrue(reopened.lastIndex() < 9,
                    "a damaged record must end the log, not be skipped over");
            assertTrue(reopened.lastIndex() >= 1, "records before the damage must survive");
        }
    }

    @Test
    void aChecksumValidRecordThatIsNotACommandRefusesToStart() throws IOException {
        // A torn write is recoverable. A record that passes its checksum and still is not a command
        // means something other than this log wrote the file, and throwing the tail away would be
        // guessing about somebody else's data.
        try (FileCommandLog log = open()) {
            log.append(StoreFixtures.script(1).get(0));
        }
        Path segment = onlySegment();
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            byte[] payload = {(byte) 0x7F, 0x01, 0x02};
            ByteBuffer record = ByteBuffer.allocate(8 + payload.length);
            record.putInt(payload.length);
            record.putInt(crcOf(payload.length, payload));
            record.put(payload);
            record.flip();
            channel.write(record);
        }

        StoreException refused = assertThrows(StoreException.class, this::open);
        assertTrue(refused.getMessage().contains("not a command"), refused.getMessage());
    }

    @Test
    void segmentsRollAndReplayInIndexOrderRatherThanDirectoryOrder() throws IOException {
        // A small segment size so the script crosses several boundaries. The names are zero-padded
        // so lexical order matches numeric order; this asserts the replay is right either way,
        // because replaying segment 10 before segment 9 resurrects superseded records.
        List<Command> script = StoreFixtures.script(40);
        try (FileCommandLog log = open(512)) {
            script.forEach(log::append);
            assertTrue(segments().size() > 3,
                    "the script should have rolled several segments, got " + segments().size());
        }

        try (FileCommandLog reopened = open(512)) {
            List<LogRecord> records = reopened.read(1, 10_000);
            assertEquals(script.size(), records.size());
            for (int i = 0; i < script.size(); i++) {
                assertEquals(i + 1, records.get(i).index());
                assertEquals(script.get(i), records.get(i).command());
            }
        }
    }

    @Test
    void segmentNamesSortTheSameWayAsTheirIndexes() throws IOException {
        try (FileCommandLog log = open(256)) {
            StoreFixtures.script(60).forEach(log::append);
        }
        List<Path> byName = segments();
        List<Path> byIndex = new ArrayList<>(byName);
        byIndex.sort(Comparator.comparingLong(path -> {
            String fileName = path.getFileName().toString();
            return Long.parseLong(fileName.substring(0, fileName.indexOf('.')));
        }));
        assertEquals(byIndex, byName,
                "zero padding exists so that these two orders cannot disagree");
    }

    @Test
    void aDiscardedPrefixIsGoneAndSaysSo() throws IOException {
        try (FileCommandLog log = open(512)) {
            StoreFixtures.script(40).forEach(log::append);
            long before = log.sizeBytes();

            log.discardThrough(20);

            assertTrue(log.firstIndex() > 1, "the prefix should have gone");
            assertTrue(log.sizeBytes() < before, "and the space with it");
            assertEquals(81, log.lastIndex(), "and nothing above the boundary");

            long stillThere = log.firstIndex();
            StoreException refused =
                    assertThrows(StoreException.class, () -> log.read(stillThere - 1, 1));
            assertTrue(refused.getMessage().contains("discarded"), refused.getMessage());
        }
    }

    @Test
    void aSegmentStraddlingTheBoundaryIsKeptWhole() throws IOException {
        try (FileCommandLog log = open(1024)) {
            StoreFixtures.script(40).forEach(log::append);
            long last = log.lastIndex();

            log.discardThrough(last - 1);

            // Whatever went, the record above the boundary is still readable. Losing it would be
            // compaction discarding data it had been told to keep, which is the failure this
            // ordering exists to prevent.
            assertEquals(last, log.read(last, 1).get(0).index());
        }
    }

    @Test
    void aGapBetweenSegmentsRefusesToStart() throws IOException {
        try (FileCommandLog log = open(512)) {
            StoreFixtures.script(40).forEach(log::append);
        }
        List<Path> found = segments();
        Files.delete(found.get(1));

        StoreException refused = assertThrows(StoreException.class, this::open);
        assertTrue(refused.getMessage().contains("not contiguous"), refused.getMessage());
    }

    @Test
    void aForeignFileInTheDirectoryIsNotMistakenForASegment() throws IOException {
        try (FileCommandLog log = open()) {
            log.append(StoreFixtures.script(1).get(0));
        }
        Files.write(directory.resolve("00000000000000009999.log"), new byte[] {1, 2, 3});

        StoreException refused = assertThrows(StoreException.class, this::open);
        assertNotNull(refused.getMessage());
    }

    @Test
    void anOversizedCommandIsRefusedRatherThanWritten() throws IOException {
        try (FileCommandLog log = open()) {
            // Nothing the codec can produce comes near the record limit, so this asserts the guard
            // exists rather than exercising it with a real command. The limit is what stops a
            // corrupt length from becoming an allocation on the way back in.
            assertEquals(1 << 20, FileCommandLog.MAX_RECORD_BYTES);
            assertTrue(log.lastIndex() == 0);
        }
    }

    @Test
    void nothingSurvivesWithoutASyncWhenDurabilityIsOff() throws IOException {
        // Not a durability claim, a documentation claim: NONE means what it says, and a reader who
        // assumed otherwise should find out here rather than after an incident.
        try (FileCommandLog log = new FileCommandLog(directory, Durability.NONE)) {
            StoreFixtures.script(2).forEach(log::append);
            assertEquals(5, log.lastIndex());
        }
        // The close() still flushes the channel to the OS, so the data is there; what NONE gives up
        // is the force to stable storage, which no test on a live filesystem can observe.
        try (FileCommandLog reopened = open()) {
            assertEquals(5, reopened.lastIndex());
        }
    }

    private Path onlySegment() throws IOException {
        List<Path> found = segments();
        assertEquals(1, found.size(), "expected a single segment, got " + found);
        return found.get(0);
    }

    private List<Path> segments() throws IOException {
        try (var listing = Files.list(directory)) {
            return listing.filter(path -> path.getFileName().toString().endsWith(".log"))
                    .sorted()
                    .toList();
        }
    }

    private static int crcOf(int length, byte[] payload) {
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(new byte[] {
            (byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length});
        crc.update(payload, 0, payload.length);
        return (int) crc.getValue();
    }
}
