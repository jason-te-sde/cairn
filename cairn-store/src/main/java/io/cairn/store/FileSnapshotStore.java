package io.cairn.store;

import io.cairn.codec.Codec;
import io.cairn.codec.CodecException;
import io.cairn.core.Registry;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Snapshots as whole files, named by the log index they describe.
 *
 * <p>Written to a temporary name, forced, then moved into place atomically, then the directory
 * itself is forced. Each of those four steps rules out one way a crash can leave a snapshot that
 * looks complete and is not: a half-written file under the real name would be loaded and trusted; a
 * file whose contents have not reached the disk would come back as zeros; a rename that has not
 * reached the disk would leave the snapshot under a name nothing looks for.
 *
 * <p>Several are kept, and that is the recovery story rather than housekeeping. {@link #load}
 * returns the newest snapshot that passes its checksum, so a torn snapshot costs a longer replay
 * instead of an outage. Refusing to start because the most recent of three files is damaged would
 * be choosing downtime over work.
 */
public final class FileSnapshotStore implements SnapshotStore {

    private static final Logger LOG = LoggerFactory.getLogger(FileSnapshotStore.class);
    private static final String SUFFIX = ".snap";
    private static final String TEMP_SUFFIX = ".tmp";

    /**
     * How many to keep.
     *
     * <p>Two, not one: keeping one means a crash during the write of the second leaves nothing
     * usable if the first has already gone, and keeping many is paying storage for snapshots that
     * a log replay reaches anyway.
     */
    static final int RETAIN = 2;

    private final Path directory;

    public FileSnapshotStore(Path directory) {
        this.directory = directory;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new StoreException("cannot open the snapshot directory " + directory, e);
        }
    }

    @Override
    public void save(Registry state) {
        byte[] framed = Codec.encodeSnapshot(state);
        Path target = directory.resolve(name(state.appliedIndex()));
        Path temporary = directory.resolve(name(state.appliedIndex()) + TEMP_SUFFIX);
        try {
            Files.write(temporary, framed,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            force(temporary);
            Files.move(temporary, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            forceDirectory();
            LOG.info("wrote snapshot at index {} ({} bytes, state {})",
                    state.appliedIndex(), framed.length, Codec.stateFingerprint(state));
            prune();
        } catch (IOException e) {
            throw new StoreException("cannot write a snapshot at index " + state.appliedIndex(), e);
        }
    }

    @Override
    public Optional<Registry> load() {
        for (Path candidate : newestFirst()) {
            try {
                Registry state = Codec.decodeSnapshot(Files.readAllBytes(candidate));
                LOG.info("loaded snapshot {} at index {}",
                        candidate.getFileName(), state.appliedIndex());
                return Optional.of(state);
            } catch (CodecException e) {
                // Loud, because it means a file that was forced to the disk came back wrong, and
                // that is worth somebody looking at even though the system recovers by itself.
                LOG.error("snapshot {} is damaged and will be skipped: {}",
                        candidate.getFileName(), e.getMessage());
            } catch (IOException e) {
                LOG.error("snapshot {} cannot be read and will be skipped: {}",
                        candidate.getFileName(), e.getMessage());
            }
        }
        return Optional.empty();
    }

    @Override
    public int count() {
        return newestFirst().size();
    }

    @Override
    public void close() {
        // Nothing held open: every operation opens and closes its own file. Stated rather than
        // left blank so a reader does not wonder what was forgotten.
    }

    /** The index of the newest snapshot on disk, or 0. Used by the metrics endpoint. */
    public long newestIndex() {
        List<Path> found = newestFirst();
        return found.isEmpty() ? 0 : indexFromName(found.get(0));
    }

    private List<Path> newestFirst() {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> listing = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
            for (Path candidate : listing) {
                found.add(candidate);
            }
        } catch (IOException e) {
            throw new StoreException("cannot list " + directory, e);
        }
        found.sort(Comparator.comparingLong(FileSnapshotStore::indexFromName).reversed());
        return found;
    }

    private void prune() throws IOException {
        List<Path> found = newestFirst();
        for (int i = RETAIN; i < found.size(); i++) {
            Files.deleteIfExists(found.get(i));
            LOG.debug("pruned old snapshot {}", found.get(i).getFileName());
        }
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private void forceDirectory() {
        try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) {
            dir.force(true);
        } catch (IOException e) {
            LOG.debug("could not force the snapshot directory: {}", e.getMessage());
        }
    }

    private static String name(long index) {
        return String.format("%020d%s", index, SUFFIX);
    }

    private static long indexFromName(Path path) {
        String fileName = path.getFileName().toString();
        try {
            return Long.parseLong(fileName.substring(0, fileName.length() - SUFFIX.length()));
        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
            throw new StoreException("not a snapshot name: " + fileName, e);
        }
    }
}
