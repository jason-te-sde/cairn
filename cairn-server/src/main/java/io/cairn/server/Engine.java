package io.cairn.server;

import io.cairn.codec.Codec;
import io.cairn.core.Command;
import io.cairn.core.Digest;
import io.cairn.core.Kernel;
import io.cairn.core.Outcome;
import io.cairn.core.PureKernel;
import io.cairn.core.Registry;
import io.cairn.core.RejectionCode;
import io.cairn.core.Transition;
import io.cairn.effects.Dispatcher;
import io.cairn.effects.EffectSink;
import io.cairn.effects.IdempotentSink;
import io.cairn.store.BlobStore;
import io.cairn.store.CommandLog;
import io.cairn.store.LogRecord;
import io.cairn.store.Recovery;
import io.cairn.store.SnapshotStore;
import io.cairn.store.StoreException;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The registry as a running thing: a log, snapshots, artifacts, the kernel, and a dispatcher.
 *
 * <h2>One thread owns the kernel</h2>
 *
 * <p>Every command becomes a task on a single-threaded executor. Nothing in the kernel takes a
 * lock, because nothing else ever touches it. Reads do not go through that thread at all: each
 * command publishes a new immutable {@link Registry} through a {@code volatile} field, so a reader
 * takes one reference and holds a whole consistent registry for as long as it likes. There is no
 * such thing here as observing a half-applied command, because there is no such value.
 *
 * <p>The dispatcher runs <b>on the same thread</b>, after each command and on a timer. That is not
 * a performance decision, it is how the re-entrancy is avoided: a dispatcher on another thread
 * would have to propose its acknowledgement back through the queue, and a proposal made from
 * inside a queued task would deadlock on itself. Keeping it on the owning thread means the
 * acknowledgement is just another append.
 *
 * <h2>What happens off that thread</h2>
 *
 * <p>Writing artifact bytes. An upload is I/O that can take minutes and must not stall the command
 * path, so it happens on the caller's thread: the bytes are streamed to the blob store, hashed on
 * the way past, and only then is {@link Command.IngestBlob} proposed. The ordering is the contract
 * — the registry never learns about an artifact that is not already on disk and verified.
 */
public final class Engine implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(Engine.class);

    private final ServerConfig config;
    private final CommandLog log;
    private final SnapshotStore snapshots;
    private final BlobStore blobs;
    private final IdempotentSink sink;
    private final Dispatcher dispatcher;
    private final ExecutorService owner;
    private final ScheduledExecutorService timer;

    /**
     * The current state.
     *
     * <p>Written only by the owning thread, read by everything. Volatile rather than synchronized
     * because an immutable value needs no more than a safely published reference.
     */
    private volatile Registry state;

    private final AtomicLong applied = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final Map<RejectionCode, AtomicLong> rejections = new ConcurrentHashMap<>();
    private volatile long lastSnapshotIndex;
    private volatile LogFigures logFigures = new LogFigures(1, 0, 0);

    public Engine(ServerConfig config, CommandLog log, SnapshotStore snapshots, BlobStore blobs,
            EffectSink downstream) {
        this.config = config;
        this.log = log;
        this.snapshots = snapshots;
        this.blobs = blobs;
        this.sink = new IdempotentSink(downstream, new io.cairn.effects.InMemoryAppliedLedger());
        this.dispatcher = new Dispatcher(this::state, sink, this::applyOnOwningThread);
        this.owner = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cairn-kernel");
            thread.setDaemon(true);
            return thread;
        });
        this.timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cairn-dispatch");
            thread.setDaemon(true);
            return thread;
        });

        try {
            // Recovery runs on the owning thread, not on whichever thread happened to construct
            // the engine. That is not tidiness: the log claims ownership from its first use, so
            // recovering here and appending later from another thread is the bug this ordering
            // exists to prevent. It was a real one — see ConcurrentReadSafetyTest.
            Recovery.Recovered recovered = onOwner(() -> Recovery.open(log, snapshots));
            this.state = recovered.state();
            this.lastSnapshotIndex = recovered.snapshotIndex();
            onOwner(() -> {
                publishLogFigures();
                return null;
            });

            timer.scheduleWithFixedDelay(
                    () -> owner.execute(this::drainQuietly),
                    config.dispatchEveryMillis(), config.dispatchEveryMillis(),
                    TimeUnit.MILLISECONDS);

            LOG.info("recovered to index {} (snapshot {}, {} log record(s) replayed), state {}",
                    state.appliedIndex(), recovered.snapshotIndex(), recovered.replayed(),
                    Codec.stateFingerprint(state));
        } catch (RuntimeException | Error failure) {
            // The engine closes these in close(), so it owns them — which means it owes them a
            // close when its own construction fails. Without this, a registry that refuses to
            // start because its log has a hole in it leaks three file handles on the way out.
            shutdownExecutors();
            closeQuietly(log, snapshots, blobs);
            throw failure;
        }
    }

    /** The current state. Lock-free, and consistent as of some point in the recent past. */
    public Registry state() {
        return state;
    }

    /** The blob store, for the read path. */
    public BlobStore blobs() {
        return blobs;
    }

    /**
     * Appends a command, applies it, then drains the outbox.
     *
     * <p>Returns once the command's durability promise has been kept and the kernel has answered.
     * Blocks the caller; the whole point of the single owning thread is that it never blocks on
     * anything but the disk.
     */
    public Outcome propose(Command command) {
        return onOwner(() -> {
            Outcome outcome = applyOnOwningThread(command);
            drainQuietly();
            maybeCheckpoint();
            return outcome;
        });
    }

    /**
     * Stores an artifact and records it.
     *
     * <p>The bytes are written and hashed on the calling thread — an upload is not something to do
     * while holding the kernel — and only then does a command go to the owning thread. If
     * {@code expected} is given it is verified rather than believed, and a mismatch stores nothing
     * and records nothing.
     *
     * @param expected the digest the caller promises, or null to be told what it is
     */
    public Ingested ingest(InputStream bytes, Digest expected) {
        BlobStore.Ingested written = expected == null
                ? blobs.put(bytes)
                : blobs.putVerified(expected, bytes);
        Outcome outcome = propose(new Command.IngestBlob(written.digest(), written.size()));
        return new Ingested(written.digest(), written.size(), outcome);
    }

    /**
     * An artifact that was stored, and what the registry said about it.
     *
     * @param digest what the bytes hash to
     * @param size how many bytes
     * @param outcome the registry's answer; a rejection means the bytes are on disk and unrecorded
     */
    public record Ingested(Digest digest, long size, Outcome outcome) {}

    /** Writes a snapshot and releases the log prefix it covers. */
    public void checkpoint() {
        onOwner(() -> {
            Recovery.checkpoint(state, log, snapshots);
            lastSnapshotIndex = state.appliedIndex();
            publishLogFigures();
            return null;
        });
    }

    /**
     * Rebuilds the state as it was at a log index.
     *
     * <p>This exists because the kernel is a pure function and the log is the whole truth, so
     * "what did the registry look like at index 4,271" is a fold rather than a feature. It is the
     * same three lines recovery runs at startup, stopped early — there is no second implementation
     * to disagree with the first, which is what makes the answer trustworthy enough to act on.
     *
     * @throws StoreException if the log prefix below {@code index} has been released and no
     *     snapshot covers it
     */
    public Registry replayTo(long index) {
        // On the owning thread, because this walks the log and the log belongs to that thread.
        // Running it on a request thread produced a ConcurrentModificationException while a
        // checkpoint rebuilt the segment list underneath it.
        //
        // The cost is that a history query briefly serializes with the command path: a replay of a
        // long log holds the owning thread for its duration. That is what a single-writer design
        // implies, and both callers are operator commands rather than hot paths —
        // docs/operations.md says so. The alternative, a second read-only handle on the same
        // directory, would have to cope with a tail being appended underneath it, which is a
        // harder problem than the one being solved.
        return onOwner(() -> replayOnOwningThread(index));
    }

    private Registry replayOnOwningThread(long index) {
        Registry base = Registry.empty();
        Optional<Registry> snapshot = snapshots.load();
        if (snapshot.isPresent() && snapshot.get().appliedIndex() <= index) {
            base = snapshot.get();
        }
        if (base.appliedIndex() + 1 < log.firstIndex()) {
            throw new io.cairn.store.HistoryUnavailableException(
                    index, earliestReplayableOnOwningThread());
        }
        PureKernel kernel = new PureKernel(base);
        long from = base.appliedIndex() + 1;
        long to = Math.min(index, log.lastIndex());
        while (from <= to) {
            List<LogRecord> batch = log.read(from, 1024);
            if (batch.isEmpty()) {
                break;
            }
            for (LogRecord record : batch) {
                if (record.index() > to) {
                    break;
                }
                kernel.apply(record.index(), record.command());
                from = record.index() + 1;
            }
        }
        return kernel.registry();
    }

    /**
     * Re-derives the whole state from durable storage and compares it to what is being served.
     *
     * <p>An end-to-end integrity audit in one call, and it is only this cheap because the encoding
     * is canonical: two states agree if and only if one SHA-256 matches. Run it after a restore, or
     * whenever somebody asks whether the registry is what the log says it is.
     */
    public Verification verify() {
        // One hop onto the owning thread for the whole audit, rather than one per step. Doing it
        // in pieces would compare a state captured before a command against a replay taken after
        // it, and report a mismatch that is really just a concurrent write.
        return onOwner(() -> {
            Registry served = state;
            Registry derived = replayOnOwningThread(log.lastIndex());
            String servedDigest = Codec.stateDigestHex(served);
            String derivedDigest = Codec.stateDigestHex(derived);
            return new Verification(
                    served.appliedIndex(), derived.appliedIndex(), servedDigest, derivedDigest,
                    servedDigest.equals(derivedDigest));
        });
    }

    /**
     * The lowest index that can still be reconstructed. Must run on the owning thread.
     *
     * <p>Either the log still reaches back to the beginning, or the newest snapshot is the floor —
     * the checkpoint ordering guarantees a snapshot exists at or above the point the log was
     * released to, so there is nothing below it to reach.
     */
    private long earliestReplayableOnOwningThread() {
        return log.firstIndex() == 1 ? 0 : lastSnapshotIndex;
    }

    /** The lowest index {@link #replayTo} can still answer for. */
    public long earliestReplayableIndex() {
        return onOwner(this::earliestReplayableOnOwningThread);
    }

    /**
     * The result of an audit.
     *
     * @param servedIndex the index the live state is at
     * @param derivedIndex the index the replay reached
     * @param servedDigest the live state's canonical digest
     * @param derivedDigest the replayed state's canonical digest
     * @param agrees whether they match
     */
    public record Verification(
            long servedIndex,
            long derivedIndex,
            String servedDigest,
            String derivedDigest,
            boolean agrees) {}

    /**
     * Counters for the metrics endpoint. Safe from any thread.
     *
     * <p>The log's own figures come from {@link #logFigures}, a snapshot the owning thread
     * republishes after every command, rather than from the log. Asking the log directly is what
     * made {@code GET /metrics} throw {@code ClosedChannelException} while a checkpoint was
     * deleting a segment. Reading a snapshot also means a scrape never queues behind an fsync,
     * which is the right trade for a monitoring endpoint: the numbers are as of the last command
     * rather than as of this instant, and they are at least consistent with each other.
     */
    public Stats stats() {
        LogFigures figures = logFigures;
        return new Stats(
                applied.get(),
                retries.get(),
                rejectionCounts(),
                dispatcher.deliveredCount(),
                sink.appliedCount(),
                sink.duplicateCount(),
                dispatcher.failureCount(),
                state.outboxDepth(),
                figures.firstIndex(),
                figures.lastIndex(),
                figures.bytes(),
                lastSnapshotIndex);
    }

    /** What the log looked like after the last command. Published by the owning thread. */
    private record LogFigures(long firstIndex, long lastIndex, long bytes) {}

    /** Must run on the owning thread. */
    private void publishLogFigures() {
        logFigures = new LogFigures(log.firstIndex(), log.lastIndex(), log.sizeBytes());
    }

    /**
     * What the engine has done.
     *
     * @param applied commands that changed something
     * @param retries commands accepted as retries
     * @param rejections refusals by code
     * @param effectsDelivered effects handed to the sink
     * @param effectsApplied effects the consumer applied
     * @param effectsDeduplicated redeliveries the consumer discarded
     * @param dispatchFailures deliveries the sink refused
     * @param outboxDepth effects waiting for delivery
     * @param logFirstIndex the lowest index still in the log
     * @param logLastIndex the highest index in the log
     * @param logBytes the log's size on disk
     * @param snapshotIndex the index of the newest snapshot
     */
    public record Stats(
            long applied,
            long retries,
            Map<RejectionCode, Long> rejections,
            long effectsDelivered,
            long effectsApplied,
            long effectsDeduplicated,
            long dispatchFailures,
            int outboxDepth,
            long logFirstIndex,
            long logLastIndex,
            long logBytes,
            long snapshotIndex) {}

    @Override
    public void close() throws IOException {
        timer.shutdownNow();
        // Drain what is owed before stopping, so a clean shutdown does not leave the outside world
        // waiting for effects the next start will have to rediscover.
        boolean closed = false;
        try {
            onOwner(() -> {
                drainQuietly();
                // Closed here, on the thread that owns them, rather than after the executor has
                // stopped. The log's own close() syncs, and a sync from another thread is exactly
                // the cross-thread use this design is trying not to make.
                closeQuietly(log, snapshots, blobs);
                return null;
            });
            closed = true;
        } catch (RuntimeException e) {
            LOG.warn("could not drain and close on the kernel thread: {}", e.getMessage());
        }
        shutdownExecutors();
        if (!closed) {
            // The owning thread was already gone, so nothing can be racing and ownership passes.
            // Better to close late than to leak the handles.
            closeQuietly(log, snapshots, blobs);
        }
    }

    private void shutdownExecutors() {
        timer.shutdownNow();
        owner.shutdown();
        try {
            if (!owner.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warn("the kernel thread did not stop within five seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(java.io.Closeable... resources) {
        for (java.io.Closeable resource : resources) {
            try {
                resource.close();
            } catch (IOException | RuntimeException e) {
                LOG.warn("could not close {} during a failed startup: {}",
                        resource.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    // ---- the owning thread -------------------------------------------------------------------

    /**
     * Appends, syncs, and applies. Must only run on the owning thread.
     *
     * <p>The order is the durability contract: the record reaches the disk before the kernel is
     * told about it, so a command this method has returned an answer for is a command that survives
     * the process.
     */
    private Outcome applyOnOwningThread(Command command) {
        long index = log.append(command);
        log.sync();
        Transition transition = Kernel.apply(state, index, command);
        state = transition.state();
        publishLogFigures();
        switch (transition.outcome()) {
            case Outcome.Applied outcome -> {
                if (outcome.kind() == Outcome.Applied.Kind.NEW) {
                    applied.incrementAndGet();
                } else {
                    retries.incrementAndGet();
                }
            }
            case Outcome.Rejected refused -> rejections
                    .computeIfAbsent(refused.code(), code -> new AtomicLong())
                    .incrementAndGet();
        }
        return transition.outcome();
    }

    private void drainQuietly() {
        try {
            Dispatcher.Run run = dispatcher.drainAll();
            if (run.failure() != null) {
                LOG.warn("delivery stalled with {} effect(s) owed: {}",
                        run.pending(), run.failure().getMessage());
            }
        } catch (RuntimeException e) {
            // A dispatcher that throws must not take the command path with it. The outbox is
            // durable, so the worst case is that delivery is late.
            LOG.error("the dispatcher failed unexpectedly", e);
        }
    }

    private void maybeCheckpoint() {
        if (config.snapshotEvery() <= 0) {
            return;
        }
        if (state.appliedIndex() - lastSnapshotIndex < config.snapshotEvery()) {
            return;
        }
        try {
            Recovery.checkpoint(state, log, snapshots);
            lastSnapshotIndex = state.appliedIndex();
            publishLogFigures();
        } catch (RuntimeException e) {
            // A failed snapshot is not a failed command. The log still holds everything, so the
            // cost is a longer replay next time.
            LOG.error("could not write a snapshot at index {}", state.appliedIndex(), e);
        }
    }

    private <T> T onOwner(Supplier<T> task) {
        try {
            return owner.submit(task::get).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the kernel", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("the kernel thread failed", cause);
        }
    }

    private Map<RejectionCode, Long> rejectionCounts() {
        Map<RejectionCode, Long> out = new java.util.EnumMap<>(RejectionCode.class);
        rejections.forEach((code, count) -> out.put(code, count.get()));
        return out;
    }

    /** Every artifact on disk that the registry has no live reference for. */
    public List<Digest> orphanedArtifacts() {
        Registry current = state;
        List<Digest> orphans = new ArrayList<>();
        for (Digest digest : blobs.list()) {
            var blob = current.blob(digest);
            if (blob.isEmpty() || !blob.get().present()) {
                orphans.add(digest);
            }
        }
        return orphans;
    }
}
