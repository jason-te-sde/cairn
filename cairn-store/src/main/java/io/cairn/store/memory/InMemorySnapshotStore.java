package io.cairn.store.memory;

import io.cairn.codec.Codec;
import io.cairn.codec.CodecException;
import io.cairn.core.Registry;
import io.cairn.store.SnapshotStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Snapshots in memory, kept as encoded bytes and newest-first, with the same
 * damaged-file-falls-back-to-an-older-one behaviour as the file implementation.
 *
 * <p>{@link #corruptNewest()} is here so the fallback can be tested at all. A recovery path that
 * has never had a damaged snapshot to recover from is a recovery path nobody has run.
 */
public final class InMemorySnapshotStore implements SnapshotStore {

    private final List<byte[]> snapshots = new ArrayList<>();
    private int saves;
    private long newestIndex;

    @Override
    public void save(Registry state) {
        snapshots.add(0, Codec.encodeSnapshot(state));
        newestIndex = state.appliedIndex();
        saves++;
        while (snapshots.size() > 2) {
            snapshots.remove(snapshots.size() - 1);
        }
    }

    @Override
    public Optional<Registry> load() {
        for (byte[] candidate : snapshots) {
            try {
                return Optional.of(Codec.decodeSnapshot(candidate));
            } catch (CodecException e) {
                // Skip it, exactly as the file store does.
                continue;
            }
        }
        return Optional.empty();
    }

    @Override
    public int count() {
        return snapshots.size();
    }

    @Override
    public void close() {
        // Nothing to release.
    }

    /** How many times a snapshot has been written, for a test that asserts one was. */
    public int saveCount() {
        return saves;
    }

    /**
     * The log index of the newest snapshot written, or 0.
     *
     * <p>Tracked rather than derived, because deriving it would mean decoding a snapshot on a path
     * whose whole purpose is to decide whether a log prefix may be released.
     */
    public long newestIndex() {
        return newestIndex;
    }

    /** Flips a bit in the newest snapshot, so recovery has to fall back to an older one. */
    public void corruptNewest() {
        if (snapshots.isEmpty()) {
            throw new IllegalStateException("there is no snapshot to corrupt");
        }
        byte[] damaged = snapshots.get(0).clone();
        damaged[damaged.length / 2] ^= 0x40;
        snapshots.set(0, damaged);
    }
}
