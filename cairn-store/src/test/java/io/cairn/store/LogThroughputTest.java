package io.cairn.store;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cairn.core.Command;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * What durability costs, measured rather than assumed.
 *
 * <p>Off by default, because a benchmark in a unit-test suite is a flaky test. Enable with
 * {@code -Dcairn.bench=true}.
 *
 * <p>The number worth having is the ratio between the three modes. It is the reason
 * {@link Durability} is a configuration choice rather than a decision made once in the code: the
 * gap between "every command survives a power loss" and "most of them do" is two orders of
 * magnitude on ordinary hardware, and which side of it you want is not something a library gets to
 * decide for an operator.
 *
 * <pre>
 * mvn test -pl cairn-store -am -Dcairn.bench=true -Dtest=LogThroughputTest \
 *     -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@EnabledIfSystemProperty(named = "cairn.bench", matches = "true")
class LogThroughputTest {

    private static final int RECORDS = 20_000;

    @TempDir
    Path directory;

    @Test
    void appendThroughputAtEachDurabilityLevel() throws IOException {
        List<Command> script = StoreFixtures.script(RECORDS / 2);

        for (Durability durability : Durability.values()) {
            // A smaller run for the slow mode: an fsync per record at a few hundred per second
            // would make this benchmark take a minute on its own.
            int count = durability == Durability.SYNC_EACH ? 2_000 : script.size();
            Path logDirectory = directory.resolve(durability.name().toLowerCase());

            try (FileCommandLog log = new FileCommandLog(logDirectory, durability)) {
                // Warm up, so the measurement is of the log rather than of class loading.
                for (int i = 0; i < 200; i++) {
                    log.append(script.get(i % script.size()));
                }
                long bytesBefore = log.sizeBytes();
                long start = System.nanoTime();
                for (int i = 0; i < count; i++) {
                    log.append(script.get(i % script.size()));
                }
                if (durability == Durability.SYNC_ON_DEMAND) {
                    log.sync();
                }
                long elapsed = System.nanoTime() - start;
                long bytes = log.sizeBytes() - bytesBefore;

                double seconds = elapsed / 1e9;
                System.out.printf(
                        "%-16s %,10.0f records/s  %,8.2f MiB/s  (%,d records in %.3fs)%n",
                        durability, count / seconds, bytes / seconds / (1024 * 1024),
                        count, seconds);
                assertTrue(count / seconds > 1, durability + " managed under one record a second");
            }
        }
    }

    @Test
    void readThroughputWithAndWithoutTheResumeHint() throws IOException {
        // The hint only helps a read that moves forward; reading the same batches in reverse
        // defeats it and re-walks the segment each time. The measurement is reported rather than
        // asserted on, because at this size it shows no difference — a rescan skips the decode for
        // records below the requested index, so it is length arithmetic over a buffered read. The
        // hint is kept for the asymptotics, not for this number, and saying so is the point of
        // printing it. See the note on Segment.readInto.
        List<Command> script = StoreFixtures.spread(RECORDS / 2, 4, 250);
        Path logDirectory = directory.resolve("reads");
        try (FileCommandLog log = new FileCommandLog(logDirectory, Durability.NONE)) {
            script.forEach(log::append);
            log.sync();

            List<Long> starts = new java.util.ArrayList<>();
            for (long index = 1; index <= log.lastIndex(); index += 1024) {
                starts.add(index);
            }

            long forward = System.nanoTime();
            int read = 0;
            for (long start : starts) {
                read += log.read(start, 1024).size();
            }
            double forwardSeconds = (System.nanoTime() - forward) / 1e9;

            List<Long> reversed = new java.util.ArrayList<>(starts);
            java.util.Collections.reverse(reversed);
            long backward = System.nanoTime();
            for (long start : reversed) {
                log.read(start, 1024);
            }
            double backwardSeconds = (System.nanoTime() - backward) / 1e9;

            System.out.printf("read forward    %,10.0f records/s%n", read / forwardSeconds);
            System.out.printf("read rescanning %,10.0f records/s  (ratio %.2f; at this size the"
                            + " hint is not what makes reads fast)%n",
                    read / backwardSeconds, backwardSeconds / forwardSeconds);
            assertTrue(read > 0);
        }
    }

    @Test
    void recoveryThroughput() throws IOException {
        // A realistic distribution: several models with a few hundred versions each, rather than
        // one model with ten thousand. The difference is not cosmetic — see
        // KernelScalingTest, which measures what happens when it is not realistic.
        List<Command> script = StoreFixtures.spread(RECORDS / 2, 4, 250);
        Path logDirectory = directory.resolve("recovery");
        try (FileCommandLog log = new FileCommandLog(logDirectory, Durability.NONE)) {
            script.forEach(log::append);
            log.sync();
        }

        FileSnapshotStore snapshots = new FileSnapshotStore(directory.resolve("snapshots"));
        long start = System.nanoTime();
        Recovery.Recovered recovered;
        try (FileCommandLog log = new FileCommandLog(logDirectory, Durability.NONE)) {
            recovered = Recovery.open(log, snapshots);
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("recovery        %,10.0f records/s  (%,d records in %.3fs)%n",
                recovered.replayed() / seconds, recovered.replayed(), seconds);
        assertTrue(recovered.replayed() > 0);
    }
}
