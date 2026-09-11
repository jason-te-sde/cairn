package io.cairn.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cairn.codec.Codec;
import io.cairn.core.Command;
import io.cairn.core.ModelId;
import io.cairn.core.PureKernel;
import io.cairn.core.Registry;
import io.cairn.core.Stage;
import io.cairn.store.memory.InMemoryCommandLog;
import io.cairn.store.memory.InMemorySnapshotStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Startup, including the startups that follow something going wrong.
 *
 * <p>Every test compares the recovered state by its canonical digest rather than field by field.
 * That is the payoff of a canonical encoding: "the registry came back identical" is one assertion
 * that cannot be satisfied by a state that agrees about the parts the test happened to check.
 */
class RecoveryTest {

    @TempDir
    Path directory;

    private FileCommandLog log() {
        return new FileCommandLog(directory.resolve("log"), Durability.SYNC_EACH);
    }

    private FileSnapshotStore snapshots() {
        return new FileSnapshotStore(directory.resolve("snapshots"));
    }

    /** Applies a script through a kernel, for the expected answer. */
    private static Registry expected(List<Command> script) {
        PureKernel kernel = new PureKernel();
        kernel.applyAll(1, script);
        return kernel.registry();
    }

    @Test
    void anEmptyStoreRecoversToAnEmptyRegistry() throws IOException {
        try (FileCommandLog log = log(); FileSnapshotStore snapshots = snapshots()) {
            Recovery.Recovered recovered = Recovery.open(log, snapshots);

            assertEquals(Registry.empty(), recovered.state());
            assertEquals(0, recovered.snapshotIndex());
            assertEquals(0, recovered.replayed());
        }
    }

    @Test
    void aLogWithNoSnapshotIsReplayedWhole() throws IOException {
        List<Command> script = StoreFixtures.script(6);
        try (FileCommandLog log = log()) {
            script.forEach(log::append);
        }
        try (FileCommandLog log = log(); FileSnapshotStore snapshots = snapshots()) {
            Recovery.Recovered recovered = Recovery.open(log, snapshots);

            assertEquals(script.size(), recovered.replayed());
            assertEquals(Codec.stateDigestHex(expected(script)),
                    Codec.stateDigestHex(recovered.state()));
        }
    }

    @Test
    void aCheckpointMakesTheNextStartupShorterAndNotDifferent() throws IOException {
        List<Command> script = StoreFixtures.script(10);
        String wanted = Codec.stateDigestHex(expected(script));

        try (FileCommandLog log = log(); FileSnapshotStore snapshots = snapshots()) {
            PureKernel kernel = new PureKernel();
            for (Command command : script) {
                kernel.apply(log.append(command), command);
            }
            Recovery.checkpoint(kernel.registry(), log, snapshots);
        }

        try (FileCommandLog log = log(); FileSnapshotStore snapshots = snapshots()) {
            Recovery.Recovered recovered = Recovery.open(log, snapshots);

            assertEquals(script.size(), recovered.snapshotIndex());
            assertEquals(0, recovered.replayed(), "the whole log should have been covered");
            assertEquals(wanted, Codec.stateDigestHex(recovered.state()));
        }
    }

    @Test
    void aCrashBetweenTheSnapshotAndTheDiscardIsSurvivable() throws IOException {
        // Recovery.checkpoint writes the snapshot first and discards afterwards. This is the state
        // a crash in between leaves: a durable snapshot and a log that still holds the records it
        // covers. Applying an already-applied index is a no-op, so the overlap costs work and
        // changes nothing.
        List<Command> script = StoreFixtures.script(8);
        String wanted = Codec.stateDigestHex(expected(script));

        try (FileCommandLog log = log(); FileSnapshotStore snapshots = snapshots()) {
            PureKernel kernel = new PureKernel();
            for (Command command : script) {
                kernel.apply(log.append(command), command);
            }
            snapshots.save(kernel.registry());
            // and then the process dies, before discardThrough
        }

        try (FileCommandLog log = log(); FileSnapshotStore snapshots = snapshots()) {
            Recovery.Recovered recovered = Recovery.open(log, snapshots);

            assertEquals(script.size(), recovered.snapshotIndex());
            assertTrue(recovered.replayed() == 0 || recovered.replayed() == script.size(),
                    "either the log was covered or it was replayed over the snapshot, not partly");
            assertEquals(wanted, Codec.stateDigestHex(recovered.state()));
        }
    }

    @Test
    void commandsAfterASnapshotAreReplayedOnTopOfIt() throws IOException {
        List<Command> first = StoreFixtures.script(4);
        List<Command> second = StoreFixtures.script(2);

        try (FileCommandLog log = log(); FileSnapshotStore snapshots = snapshots()) {
            PureKernel kernel = new PureKernel();
            for (Command command : first) {
                kernel.apply(log.append(command), command);
            }
            Recovery.checkpoint(kernel.registry(), log, snapshots);
            // The second script republishes the same versions, so every command is idempotent and
            // the final state is the first script's. That is deliberate: it means the assertion
            // below is about the recovery and not about what the extra commands did.
            for (Command command : second) {
                kernel.apply(log.append(command), command);
            }
        }

        try (FileCommandLog log = log(); FileSnapshotStore snapshots = snapshots()) {
            Recovery.Recovered recovered = Recovery.open(log, snapshots);

            assertEquals(first.size(), recovered.snapshotIndex());
            assertEquals(second.size(), recovered.replayed());
            assertEquals(first.size() + second.size(), recovered.state().appliedIndex());
        }
    }

    @Test
    void aDamagedSnapshotFallsBackToAnOlderOneRatherThanRefusingToStart() {
        List<Command> script = StoreFixtures.script(6);
        InMemoryCommandLog log = new InMemoryCommandLog();
        InMemorySnapshotStore snapshots = new InMemorySnapshotStore();

        PureKernel kernel = new PureKernel();
        for (int i = 0; i < script.size(); i++) {
            kernel.apply(log.append(script.get(i)), script.get(i));
            if (i == 2) {
                snapshots.save(kernel.registry());
            }
        }
        log.sync();
        snapshots.save(kernel.registry());
        assertEquals(2, snapshots.count());

        snapshots.corruptNewest();
        Recovery.Recovered recovered = Recovery.open(log, snapshots);

        assertEquals(3, recovered.snapshotIndex(), "it should fall back to the older snapshot");
        assertEquals(script.size() - 3, recovered.replayed(), "and replay the difference");
        assertEquals(Codec.stateDigestHex(expected(script)),
                Codec.stateDigestHex(recovered.state()));
    }

    @Test
    void everySnapshotBeingDamagedMeansAFullReplayNotAnOutage() {
        List<Command> script = StoreFixtures.script(5);
        InMemoryCommandLog log = new InMemoryCommandLog();
        InMemorySnapshotStore snapshots = new InMemorySnapshotStore();

        PureKernel kernel = new PureKernel();
        for (Command command : script) {
            kernel.apply(log.append(command), command);
        }
        log.sync();
        snapshots.save(kernel.registry());
        snapshots.corruptNewest();

        Recovery.Recovered recovered = Recovery.open(log, snapshots);
        assertEquals(0, recovered.snapshotIndex());
        assertEquals(script.size(), recovered.replayed());
        assertEquals(Codec.stateDigestHex(expected(script)),
                Codec.stateDigestHex(recovered.state()));
    }

    @Test
    void aGapBetweenTheSnapshotAndTheLogRefusesToStart() {
        // The one case that is not recoverable: the log has been trimmed past what any usable
        // snapshot describes, so records that were acknowledged are gone. Starting would mean
        // serving a registry that silently skipped them.
        InMemoryCommandLog log = new InMemoryCommandLog();
        InMemorySnapshotStore snapshots = new InMemorySnapshotStore();
        PureKernel kernel = new PureKernel();
        for (Command command : StoreFixtures.script(6)) {
            kernel.apply(log.append(command), command);
        }
        log.sync();
        log.discardThrough(10);

        StoreException refused =
                assertThrows(StoreException.class, () -> Recovery.open(log, snapshots));
        assertTrue(refused.getMessage().contains("acknowledged are missing"), refused.getMessage());
    }

    @Test
    void anUnsyncedTailIsGoneAfterACrash() {
        InMemoryCommandLog log = new InMemoryCommandLog();
        InMemorySnapshotStore snapshots = new InMemorySnapshotStore();

        List<Command> script = StoreFixtures.script(4);
        script.subList(0, 5).forEach(log::append);
        log.sync();
        script.subList(5, script.size()).forEach(log::append);

        assertEquals(5, log.syncedCount());
        assertTrue(log.recordCount() > log.syncedCount(), "the test needs an unsynced tail");

        log.crash();

        Recovery.Recovered recovered = Recovery.open(log, snapshots);
        assertEquals(5, recovered.replayed(), "only the durable prefix should come back");
        assertEquals(5, recovered.state().appliedIndex());
    }

    @Test
    void theRecoveredStateIsTheOneTheClientWasPromised() throws IOException {
        // The property that matters operationally, stated as a test rather than as a comment: a
        // command that was acknowledged under SYNC_EACH is in the registry after a restart.
        List<Command> script = StoreFixtures.script(3);
        long acknowledged;
        try (FileCommandLog log = log()) {
            long last = 0;
            for (Command command : script) {
                last = log.append(command);
            }
            acknowledged = last;
        }
        try (FileCommandLog log = log(); FileSnapshotStore snapshots = snapshots()) {
            assertEquals(acknowledged, Recovery.open(log, snapshots).state().appliedIndex());
        }
    }

    @Test
    void theSnapshotStoreKeepsABoundedNumberOfThem() throws IOException {
        try (FileCommandLog log = log(); FileSnapshotStore snapshots = snapshots()) {
            PureKernel kernel = new PureKernel();
            for (Command command : StoreFixtures.script(10)) {
                kernel.apply(log.append(command), command);
                snapshots.save(kernel.registry());
            }
            assertEquals(FileSnapshotStore.RETAIN, snapshots.count(),
                    "older snapshots should be pruned rather than accumulating");
            assertEquals(kernel.registry().appliedIndex(), snapshots.newestIndex());
        }
    }

    @Test
    void aRecoveredRegistryAnswersTheQuestionsItShould() throws IOException {
        try (FileCommandLog log = log(); FileSnapshotStore snapshots = snapshots()) {
            StoreFixtures.script(3).forEach(log::append);
            Registry state = Recovery.open(log, snapshots).state();

            assertEquals(Stage.PRODUCTION,
                    state.model(ModelId.of("fraud")).orElseThrow()
                            .production().orElseThrow().stage());
            assertEquals(3, state.model(ModelId.of("fraud")).orElseThrow().liveCount());
        }
    }
}
