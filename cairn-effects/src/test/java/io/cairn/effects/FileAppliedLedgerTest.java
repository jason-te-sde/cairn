package io.cairn.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The durable watermark, including what happens when it is damaged. */
class FileAppliedLedgerTest {

    @TempDir
    Path directory;

    @Test
    void anAbsentLedgerStartsAtZero() {
        try (FileAppliedLedger ledger = new FileAppliedLedger(directory.resolve("applied"))) {
            assertEquals(0, ledger.applied());
        }
    }

    @Test
    void aRecordedWatermarkSurvivesAReopen() {
        Path file = directory.resolve("applied");
        try (FileAppliedLedger ledger = new FileAppliedLedger(file)) {
            ledger.record(4_271);
        }
        try (FileAppliedLedger reopened = new FileAppliedLedger(file)) {
            assertEquals(4_271, reopened.applied());
        }
    }

    @Test
    void recordingTheSameValueTwiceIsNotAWrite() throws IOException {
        Path file = directory.resolve("applied");
        try (FileAppliedLedger ledger = new FileAppliedLedger(file)) {
            ledger.record(7);
            var firstWrite = Files.getLastModifiedTime(file);
            ledger.record(7);
            assertEquals(firstWrite, Files.getLastModifiedTime(file));
        }
    }

    @Test
    void aDamagedWatermarkThrowsRatherThanDefaultingToZero() throws IOException {
        // Defaulting to zero would replay every effect the consumer ever applied. For a cache that
        // is a warm-up; for a collector it is deleting artifacts a second time. A library cannot
        // tell which one it is talking to, so it refuses.
        Path file = directory.resolve("applied");
        try (FileAppliedLedger ledger = new FileAppliedLedger(file)) {
            ledger.record(99);
        }
        byte[] content = Files.readAllBytes(file);
        content[8] ^= 0x10;
        Files.write(file, content);

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> new FileAppliedLedger(file));
        assertTrue(refused.getMessage().contains("checksum"), refused.getMessage());
    }

    @Test
    void aTruncatedWatermarkIsRefused() throws IOException {
        Path file = directory.resolve("applied");
        try (FileAppliedLedger ledger = new FileAppliedLedger(file)) {
            ledger.record(99);
        }
        Files.write(file, new byte[] {0x43, 0x52, 0x4E, 0x44});

        IllegalStateException refused =
                assertThrows(IllegalStateException.class, () -> new FileAppliedLedger(file));
        assertTrue(refused.getMessage().contains("damaged"), refused.getMessage());
    }

    @Test
    void aForeignFileIsRefused() throws IOException {
        Path file = directory.resolve("applied");
        Files.write(file, new byte[17]);
        assertThrows(IllegalStateException.class, () -> new FileAppliedLedger(file));
    }

    @Test
    void noTemporaryFileIsLeftBehind() throws IOException {
        Path file = directory.resolve("nested").resolve("applied");
        try (FileAppliedLedger ledger = new FileAppliedLedger(file)) {
            ledger.record(1);
            ledger.record(2);
        }
        try (var listing = Files.list(file.getParent())) {
            assertEquals(
                    java.util.List.of(file.getFileName().toString()),
                    listing.map(path -> path.getFileName().toString()).sorted().toList(),
                    "a crash mid-write must not leave a .tmp file that looks like a ledger");
        }
    }
}
