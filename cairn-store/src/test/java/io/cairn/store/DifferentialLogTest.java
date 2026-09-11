package io.cairn.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cairn.codec.Codec;
import io.cairn.core.Command;
import io.cairn.core.Digest;
import io.cairn.core.PureKernel;
import io.cairn.core.Ref;
import io.cairn.core.Stage;
import io.cairn.store.memory.InMemoryCommandLog;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The two log implementations, driven by the same random operation stream, required to agree.
 *
 * <p>Worth its own file because of what this class of test has caught before. In this project's
 * predecessor, a differential test against an in-memory store was the only thing that found a
 * replay-ordering bug — segments were being replayed in the order the directory listed them, so a
 * superseding append that created a lower-numbered file later resurrected records it had replaced.
 * No hand-written test came near it, because nobody writes a test for an ordering they believe is
 * obviously correct.
 *
 * <p>The operations include rolling segments, discarding prefixes, reopening from disk and reading
 * from arbitrary positions, so the shapes that ordering bugs hide in are all reachable. The seed is
 * a parameter and is printed on failure.
 */
class DifferentialLogTest {

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 7, 42, 8123, 20260911})
    void theFileLogAndTheInMemoryLogAgree(long seed) throws IOException {
        Random random = new Random(seed);
        // A deliberately small segment so the file log rolls constantly; the in-memory log has no
        // segments at all, which is the asymmetry the comparison is meant to expose.
        Path logDirectory = directory.resolve("log-" + seed);
        FileCommandLog file = new FileCommandLog(logDirectory, Durability.SYNC_ON_DEMAND, 400);
        InMemoryCommandLog memory = new InMemoryCommandLog();

        int reopens = 0;
        int discards = 0;
        int appends = 0;

        try {
            for (int step = 0; step < 400; step++) {
                int choice = random.nextInt(100);
                if (choice < 70) {
                    Command command = randomCommand(random, step);
                    long fileIndex = file.append(command);
                    long memoryIndex = memory.append(command);
                    assertEquals(memoryIndex, fileIndex,
                            "the two logs assigned different indexes at step " + step
                                    + " (seed " + seed + ")");
                    appends++;
                } else if (choice < 80) {
                    file.sync();
                    memory.sync();
                } else if (choice < 90) {
                    assertSameContents(file, memory, seed, step);
                } else if (choice < 96) {
                    long last = file.lastIndex();
                    if (last > 4) {
                        long through = file.firstIndex()
                                + random.nextInt((int) (last - file.firstIndex() + 1)) - 1;
                        if (through >= file.firstIndex()) {
                            long fileBefore = file.firstIndex();
                            long memoryBefore = memory.firstIndex();
                            file.discardThrough(through);
                            memory.discardThrough(through);
                            discards++;
                            assertNothingAboveTheBoundaryWasLost(file, fileBefore, through, seed);
                            assertNothingAboveTheBoundaryWasLost(
                                    memory, memoryBefore, through, seed);
                        }
                    }
                } else {
                    file.sync();
                    memory.sync();
                    file.close();
                    file = new FileCommandLog(logDirectory, Durability.SYNC_ON_DEMAND, 400);
                    reopens++;
                    assertSameContents(file, memory, seed, step);
                }
            }

            assertSameContents(file, memory, seed, -1);

            // A differential test that never exercised the interesting operations would pass
            // because both sides did nothing. Assert the run was actually varied.
            assertTrue(appends > 200, "seed " + seed + " only appended " + appends + " records");
            assertTrue(discards > 0, "seed " + seed + " never discarded a prefix");
            assertTrue(reopens > 0, "seed " + seed + " never reopened the file log");
        } finally {
            file.close();
            memory.close();
        }
    }

    /**
     * Compares what the port guarantees, which is not "the two logs are identical".
     *
     * <p>{@code discardThrough} is allowed to keep more of the prefix than it was asked to, because
     * a segmented log can only free whole files. So the comparison is over the range both logs
     * still hold — and it separately asserts that neither log lost a record it was told to keep,
     * which is the guarantee that actually matters.
     */
    private static void assertSameContents(
            CommandLog file, CommandLog memory, long seed, int step) {
        String where = " (seed " + seed + (step < 0 ? ", at the end" : ", step " + step) + ")";
        assertEquals(memory.lastIndex(), file.lastIndex(), "last index differs" + where);

        long from = Math.max(file.firstIndex(), memory.firstIndex());
        List<LogRecord> fromFile = file.read(from, 10_000);
        List<LogRecord> fromMemory = memory.read(from, 10_000);
        assertEquals(fromMemory.size(), fromFile.size(), "record count differs" + where);
        for (int i = 0; i < fromMemory.size(); i++) {
            assertEquals(fromMemory.get(i).index(), fromFile.get(i).index(),
                    "index at position " + i + " differs" + where);
            assertEquals(fromMemory.get(i).command(), fromFile.get(i).command(),
                    "command at index " + fromMemory.get(i).index() + " differs" + where);
        }

        // And the states they fold to are identical, which is the property anybody actually cares
        // about: two logs agree if replaying them produces the same registry.
        assertEquals(foldDigest(fromMemory), foldDigest(fromFile), "folded state differs" + where);
    }

    /**
     * A discard may not take anything the call was told to keep.
     *
     * <p>Stated per call, not cumulatively: an earlier discard with a higher boundary may already
     * have released records that this call's boundary would have kept, and that is not this call
     * losing them. So the bound is on how far {@code firstIndex()} may move, given where it
     * started.
     */
    private static void assertNothingAboveTheBoundaryWasLost(
            CommandLog log, long firstIndexBefore, long boundary, long seed) {
        long allowed = Math.max(firstIndexBefore, boundary + 1);
        assertTrue(log.firstIndex() <= allowed,
                "seed " + seed + ": discardThrough(" + boundary + ") moved the log start from "
                        + firstIndexBefore + " to " + log.firstIndex()
                        + ", past the highest it was allowed to reach (" + allowed + ")");
        if (log.firstIndex() <= log.lastIndex()) {
            assertEquals(log.firstIndex(), log.read(log.firstIndex(), 1).get(0).index(),
                    "seed " + seed + ": the log's own first index is not readable");
        }
    }

    /**
     * Folds a run of records into a state digest, tolerating a discarded prefix.
     *
     * <p>A prefix that has been discarded cannot be replayed from an empty registry, so this starts
     * the kernel at the run's own first index. The comparison is still meaningful: both sides are
     * folded the same way, so a disagreement is a disagreement about the records.
     */
    private static String foldDigest(List<LogRecord> records) {
        if (records.isEmpty()) {
            return "empty";
        }
        long firstIndex = records.get(0).index();
        PureKernel kernel = new PureKernel(new io.cairn.core.Registry(
                null, null, null, 1, 0, firstIndex - 1));
        for (LogRecord record : records) {
            kernel.apply(record.index(), record.command());
        }
        return Codec.stateDigestHex(kernel.registry());
    }

    private static Command randomCommand(Random random, int step) {
        Digest digest = digestFor(random.nextInt(12));
        return switch (random.nextInt(4)) {
            case 0 -> new Command.IngestBlob(digest, 1024L * (1 + random.nextInt(64)));
            case 1 -> Command.PublishVersion.of(
                    Ref.of("m" + random.nextInt(4), "1.0." + random.nextInt(20)),
                    digest, "actor" + random.nextInt(3), step);
            case 2 -> new Command.Promote(
                    Ref.of("m" + random.nextInt(4), "1.0." + random.nextInt(20)),
                    Stage.values()[random.nextInt(Stage.values().length)],
                    "actor" + random.nextInt(3), step);
            default -> new Command.AckEffects(random.nextInt(50));
        };
    }

    private static Digest digestFor(int n) {
        try {
            return Digest.ofSha256(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("artifact-" + n).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
