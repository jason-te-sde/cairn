package io.cairn.store.memory;

import io.cairn.codec.Codec;
import io.cairn.core.Command;
import io.cairn.store.CommandLog;
import io.cairn.store.LogRecord;
import io.cairn.store.StoreException;
import java.util.ArrayList;
import java.util.List;

/**
 * A command log in memory that models the one property the real one promises: a write that has not
 * been forced does not survive a crash.
 *
 * <p>Without that, a simulated crash is a no-op and every crash test passes vacuously. With it, the
 * simulator can inject the failure that actually matters — a process that appended, answered a
 * client, and died before the record reached the disk — and assert that the registry that comes
 * back is one the client's answer was consistent with.
 *
 * <p>Records are held <b>encoded</b>, not as objects. That costs a little speed and buys two
 * things: the codec is exercised by every simulation rather than only by its own tests, and a
 * command cannot be mutated through a reference the log is still holding. It is a deliberate
 * difference from the usual advice to keep a simulator free of serialization — here the encoding is
 * canonical, so it is part of the thing being tested rather than noise around it.
 */
public final class InMemoryCommandLog implements CommandLog {

    private final List<byte[]> records = new ArrayList<>();
    private long firstIndex = 1;
    private int syncedCount;

    @Override
    public long append(Command command) {
        records.add(Codec.encodeCommand(command));
        return lastIndex();
    }

    @Override
    public void sync() {
        syncedCount = records.size();
    }

    @Override
    public long lastIndex() {
        return firstIndex + records.size() - 1;
    }

    @Override
    public long firstIndex() {
        return firstIndex;
    }

    @Override
    public List<LogRecord> read(long fromIndex, int maxRecords) {
        if (fromIndex < firstIndex) {
            throw new StoreException(
                    "index " + fromIndex + " has been discarded; the log starts at " + firstIndex);
        }
        List<LogRecord> out = new ArrayList<>();
        for (long index = fromIndex; index <= lastIndex() && out.size() < maxRecords; index++) {
            out.add(new LogRecord(index, Codec.decodeCommand(records.get((int) (index - firstIndex)))));
        }
        return out;
    }

    @Override
    public void discardThrough(long throughIndex) {
        while (firstIndex <= throughIndex && !records.isEmpty()) {
            records.remove(0);
            firstIndex++;
            if (syncedCount > 0) {
                syncedCount--;
            }
        }
    }

    @Override
    public long sizeBytes() {
        long total = 0;
        for (byte[] record : records) {
            total += record.length + 8;
        }
        return total;
    }

    @Override
    public void close() {
        // Nothing to release.
    }

    /**
     * Loses everything that was not forced, as a power failure would.
     *
     * <p>The log object survives, because a crash destroys the process and not the disk. What is
     * gone is exactly what {@link #sync()} had not yet promised.
     */
    public void crash() {
        while (records.size() > syncedCount) {
            records.remove(records.size() - 1);
        }
    }

    /** How many records are durable. For a test that wants to assert the crash was meaningful. */
    public int syncedCount() {
        return syncedCount;
    }

    /**
     * The highest index that would survive a crash, or {@code firstIndex() - 1} if none would.
     *
     * <p>Exists so a simulation can enforce the rule the real system depends on: a replica must not
     * apply a record before it is durable. Without that rule, a replica applies index 10, the
     * process loses power, the log comes back reaching only 8, and the replica's state has gone
     * backwards — which would look like a monotonicity violation and would in fact be the
     * simulation cheating.
     */
    public long durableIndex() {
        return firstIndex + syncedCount - 1;
    }

    /** How many records are present, durable or not. */
    public int recordCount() {
        return records.size();
    }
}
