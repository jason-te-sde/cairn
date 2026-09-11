package io.cairn.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cairn.codec.Codec;
import io.cairn.core.Command;
import io.cairn.core.Digest;
import io.cairn.core.Ref;
import io.cairn.core.Stage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Everything a request thread may touch, touched while the owning thread is writing.
 *
 * <p>{@code FileCommandLog}'s own javadoc says implementations are not thread-safe and a single
 * thread owns the append path. The engine is what has to honour that, and these tests are how it is
 * held to it: they drive commands on the owning thread while request threads hammer the read paths
 * that go anywhere near the log.
 */
class ConcurrentReadSafetyTest {

    @TempDir
    Path directory;

    private TestRegistry registry;

    @BeforeEach
    void start() throws IOException {
        registry = new TestRegistry(directory);
    }

    @AfterEach
    void stop() throws IOException {
        registry.close();
    }

    private Engine engine() {
        return registry.engine();
    }

    private Digest ingest(String content) {
        return engine().ingest(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), null).digest();
    }

    /**
     * Runs {@code readers} threads over {@code read} while the owning thread applies commands.
     *
     * @param minReads how much work the readers must get through for the run to be meaningful.
     *     This differs per reader by an order of magnitude and that is the point: scraping metrics
     *     is a few field reads off a volatile snapshot, while a history query is a full log replay
     *     that runs ON the owning thread and therefore queues behind every command. Calibrating
     *     both to the same number would either make the cheap test toothless or the expensive one
     *     impossible — the first version of this file used 50 for everything, which was a number
     *     taken from the racy behaviour it was written to catch.
     */
    private List<Throwable> hammer(Runnable read, int readers, int minReads)
            throws InterruptedException {
        Digest artifact = ingest("weights");
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch stop = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(readers);
        for (int i = 0; i < readers; i++) {
            pool.submit(() -> {
                while (stop.getCount() > 0) {
                    try {
                        read.run();
                        reads.incrementAndGet();
                    } catch (Throwable failure) {
                        failures.add(failure);
                        return;
                    }
                }
            });
        }

        // Enough commands to roll segments and take snapshots underneath the readers.
        for (int i = 0; i < 400; i++) {
            engine().propose(Command.PublishVersion.of(
                    Ref.of("m" + (i % 3), "1.0." + i), artifact, "alice", i));
            if (i % 120 == 0) {
                engine().checkpoint();
            }
        }
        stop.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "readers did not stop");
        assertTrue(reads.get() >= minReads,
                "the readers only managed " + reads.get() + " reads, wanted " + minReads
                        + "; the window this test needs was not open");
        return failures;
    }

    @Test
    void metricsCanBeScrapedWhileCommandsAreApplied() throws InterruptedException {
        // Cheap: a volatile snapshot and some counters, so thousands of scrapes fit in the window.
        List<Throwable> failures =
                hammer(() -> Metrics.render(engine().state(), engine().stats()), 4, 200);
        assertEquals(List.of(), failures,
                "scraping /metrics during writes threw: " + describe(failures));
    }

    @Test
    void historyCanBeReadWhileCommandsAreApplied() throws InterruptedException {
        // /v1/state?at=N. This one runs the whole log read path, which is the same code the owning
        // thread is appending through.
        // Expensive: each read is a full replay on the owning thread, so a handful is a lot of
        // work rather than a weak test.
        List<Throwable> failures = hammer(() -> {
            long at = engine().state().appliedIndex();
            try {
                engine().replayTo(Math.max(1, at - 5));
            } catch (io.cairn.store.HistoryUnavailableException gone) {
                // Legitimate: a checkpoint released the prefix between reading the current index
                // and asking about an earlier one. That is the answer, not a fault — and it is why
                // the API returns 410 with the earliest index still available rather than a 409.
                assertTrue(gone.earliestAvailable() >= 0, gone.getMessage());
            }
        }, 4, 5);
        assertEquals(List.of(), failures,
                "reading history during writes threw: " + describe(failures));
    }

    @Test
    void verifyCanBeRunWhileCommandsAreApplied() throws InterruptedException {
        List<Throwable> failures = hammer(() -> {
            Engine.Verification result = engine().verify();
            if (!result.agrees()) {
                throw new AssertionError("verify disagreed: " + result);
            }
        }, 3, 3);
        assertEquals(List.of(), failures,
                "verify during writes threw or disagreed: " + describe(failures));
    }

    @Test
    void theStateAReplayProducesIsAlwaysOneTheKernelCouldHaveProduced() throws InterruptedException {
        // A torn read would most likely surface as a decode failure, but it could also surface as a
        // state that simply never existed. This checks the digest of every replayed state against
        // a replay of the same index done afterwards, quietly, with nothing else running.
        Digest artifact = ingest("weights");
        for (int i = 0; i < 40; i++) {
            engine().propose(Command.PublishVersion.of(
                    Ref.of("m", "1.0." + i), artifact, "alice", i));
        }
        long target = engine().state().appliedIndex() - 3;
        String quiet = Codec.stateDigestHex(engine().replayTo(target));

        List<Throwable> failures = hammer(() -> {
            String busy;
            try {
                busy = Codec.stateDigestHex(engine().replayTo(target));
            } catch (io.cairn.store.HistoryUnavailableException gone) {
                return;
            }
            if (!busy.equals(quiet)) {
                throw new AssertionError(
                        "replay to " + target + " gave " + busy + " under load, " + quiet + " idle");
            }
        }, 4, 5);
        assertEquals(List.of(), failures, describe(failures));
    }

    @Test
    void promotingWhileReadingNeverExposesTwoProductionVersions() throws InterruptedException {
        Digest artifact = ingest("weights");
        for (int i = 0; i < 20; i++) {
            engine().propose(Command.PublishVersion.of(
                    Ref.of("fraud", "1.0." + i), artifact, "alice", i));
        }
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch stop = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        for (int i = 0; i < 3; i++) {
            pool.submit(() -> {
                while (stop.getCount() > 0) {
                    try {
                        var model = engine().state()
                                .model(io.cairn.core.ModelId.of("fraud")).orElse(null);
                        if (model == null) {
                            continue;
                        }
                        long inProduction = model.versions().values().stream()
                                .filter(v -> v.live() && v.stage() == Stage.PRODUCTION)
                                .count();
                        if (inProduction > 1) {
                            throw new AssertionError("observed " + inProduction + " in production");
                        }
                    } catch (Throwable failure) {
                        failures.add(failure);
                        return;
                    }
                }
            });
        }
        for (int i = 0; i < 20; i++) {
            engine().propose(new Command.Promote(
                    Ref.of("fraud", "1.0." + i), Stage.PRODUCTION, "alice", i));
        }
        stop.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(List.of(), failures, describe(failures));
    }

    @Test
    void aHistoryQueryHoldsTheOwningThreadAndTheCostIsReported() {
        // The price of the fix, measured rather than asserted on. replayTo runs on the owning
        // thread, so a history query over a long log delays the commands behind it. This prints the
        // figure that docs/operations.md quotes; there is no threshold, because a shared CI runner
        // is not a basis for one.
        Digest artifact = ingest("weights");
        for (int i = 0; i < 2_000; i++) {
            engine().propose(Command.PublishVersion.of(
                    Ref.of("m" + (i % 20), "1.0." + i), artifact, "alice", i));
        }
        long index = engine().state().appliedIndex();

        engine().replayTo(index);
        long start = System.nanoTime();
        int rounds = 20;
        for (int i = 0; i < rounds; i++) {
            engine().replayTo(index);
        }
        double millis = (System.nanoTime() - start) / 1e6 / rounds;
        System.out.printf(
                "replayTo over %d records holds the kernel thread for %.1f ms per call%n",
                index, millis);
        assertTrue(millis >= 0);
    }

    private static String describe(List<Throwable> failures) {
        if (failures.isEmpty()) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        for (Throwable failure : failures.subList(0, Math.min(3, failures.size()))) {
            out.append("\n  ").append(failure.getClass().getName())
                    .append(": ").append(failure.getMessage());
        }
        return out.toString();
    }
}
