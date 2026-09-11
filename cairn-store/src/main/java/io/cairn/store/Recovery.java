package io.cairn.store;

import io.cairn.core.PureKernel;
import io.cairn.core.Registry;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup: load the newest usable snapshot, then replay the log above it.
 *
 * <p>A fold over a pure function, which is the whole reason this is twenty lines rather than a
 * subsystem. It is also the only code path that reconstructs state, so the recovery that runs at
 * boot and the {@code cairnctl replay --at} that answers "how did this get into production" are
 * the same code with a different stopping point — there is no second implementation to disagree
 * with the first.
 *
 * <p>Two conditions abort rather than recover, and the distinction matters. A damaged record at the
 * <i>tail</i> of the log is an unsynced write and is truncated by {@link FileCommandLog}, because
 * nothing was ever acknowledged past it. A <i>gap</i> between the snapshot and the log is
 * different: it means records that were acknowledged are gone, and starting up would mean serving a
 * registry that skipped them without anybody being told.
 */
public final class Recovery {

    private static final Logger LOG = LoggerFactory.getLogger(Recovery.class);

    private static final int BATCH = 1024;

    private Recovery() {}

    /**
     * What recovery found.
     *
     * @param kernel a kernel over the reconstructed state
     * @param snapshotIndex the index of the snapshot it started from, or 0 if there was none
     * @param replayed how many log records were applied on top
     */
    public record Recovered(PureKernel kernel, long snapshotIndex, long replayed) {

        /** The reconstructed state. */
        public Registry state() {
            return kernel.registry();
        }
    }

    /** Reconstructs the registry from durable state. */
    public static Recovered open(CommandLog log, SnapshotStore snapshots) {
        Optional<Registry> snapshot = snapshots.load();
        Registry base = snapshot.orElseGet(Registry::empty);
        long from = base.appliedIndex() + 1;

        if (log.firstIndex() > from && log.lastIndex() >= from) {
            throw new StoreException(
                    "the log starts at " + log.firstIndex() + " but the newest usable snapshot"
                            + " only reaches " + base.appliedIndex()
                            + "; records that were acknowledged are missing");
        }

        PureKernel kernel = new PureKernel(base);
        long replayed = 0;
        long index = from;
        while (index <= log.lastIndex()) {
            List<LogRecord> batch = log.read(index, BATCH);
            if (batch.isEmpty()) {
                throw new StoreException(
                        "the log claims to reach " + log.lastIndex() + " but returned nothing at "
                                + index);
            }
            for (LogRecord record : batch) {
                // No guard against an index at or below the snapshot's: Kernel.apply is idempotent
                // in the log index, so an overlap between the snapshot and the log is a no-op
                // rather than something this loop has to be careful about.
                kernel.apply(record.index(), record.command());
                replayed++;
                index = record.index() + 1;
            }
        }

        LOG.info("recovered to index {} from snapshot {} plus {} log record(s)",
                kernel.registry().appliedIndex(), base.appliedIndex(), replayed);
        return new Recovered(kernel, base.appliedIndex(), replayed);
    }

    /**
     * Writes a snapshot, then discards the log prefix it covers.
     *
     * <p>This ordering is the only content of this method, and it is why the method exists at all
     * rather than being two calls at each site that needs them. A crash between the two steps
     * leaves a durable snapshot and a log that still holds records below it, which recovery handles
     * because applying an already-applied index is a no-op. A crash between them in the other order
     * leaves a log that has lost records no snapshot describes, which is unrecoverable.
     */
    public static void checkpoint(Registry state, CommandLog log, SnapshotStore snapshots) {
        snapshots.save(state);
        log.discardThrough(state.appliedIndex());
    }
}
