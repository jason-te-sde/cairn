package io.cairn.store;

import io.cairn.core.Registry;
import java.io.Closeable;
import java.util.Optional;

/**
 * Somewhere to put a whole registry so the log below it can be discarded.
 *
 * <p>A snapshot here is a value, not a checkpoint of a live database. The registry this project is
 * derived from restored by pointing its live RocksDB handle at the checkpoint directory, so every
 * write after a restore landed inside the snapshot and was lost the next time one was taken. That
 * failure is not expressible against this interface: {@link #load} hands back an immutable
 * {@link Registry}, and there is nothing to point at anything.
 */
public interface SnapshotStore extends Closeable {

    /**
     * Writes a snapshot and forces it to the disk.
     *
     * <p>Returns only once the snapshot would survive a power loss, because the caller's next step
     * is to discard the log prefix it describes.
     */
    void save(Registry state);

    /**
     * The newest usable snapshot.
     *
     * <p>"Usable" rather than "newest": a snapshot that fails its checksum is skipped in favour of
     * an older one, loudly. Refusing to start because the most recent of several snapshots is
     * damaged would be choosing an outage over replaying a few more log records.
     */
    Optional<Registry> load();

    /** How many snapshots are being kept. */
    int count();
}
