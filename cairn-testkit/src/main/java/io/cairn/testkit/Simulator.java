package io.cairn.testkit;

import io.cairn.core.Command;
import io.cairn.core.Digest;
import io.cairn.core.Effect;
import io.cairn.core.ModelId;
import io.cairn.core.Outcome;
import io.cairn.core.PureKernel;
import io.cairn.core.RegistryView;
import io.cairn.core.RejectionCode;
import io.cairn.core.SequencedEffect;
import io.cairn.core.VersionId;
import io.cairn.effects.AppliedLedger;
import io.cairn.effects.Dispatcher;
import io.cairn.effects.EffectSink;
import io.cairn.effects.IdempotentSink;
import io.cairn.effects.InMemoryAppliedLedger;
import io.cairn.store.BlobStore;
import io.cairn.store.Recovery;
import io.cairn.store.SnapshotStore;
import io.cairn.store.memory.InMemoryBlobStore;
import io.cairn.store.memory.InMemoryCommandLog;
import io.cairn.store.memory.InMemorySnapshotStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A whole registry, its replicas, its disks and its downstream world, as a function of one integer
 * seed.
 *
 * <h2>What is simulated, and what is not</h2>
 *
 * <p>cairn is a state machine, not a consensus protocol — {@code docs/design/0005-scope.md} has the
 * reasoning. So the simulator models <b>one agreed log</b>, which is what consensus produces, and
 * <b>several replicas applying it</b>, which is what cairn is responsible for. Everything that can
 * go wrong between the log and a replica's state is injected: a replica losing its memory and
 * rebuilding from its own snapshot plus the log, a snapshot taken at an awkward moment, a log
 * prefix released, an entry delivered twice, a power loss between an append and its sync, a
 * downstream consumer that refuses deliveries and later recovers, and a dispatcher that restarts
 * having forgotten what it had sent.
 *
 * <p>What is not simulated is disagreement about the <i>order</i>. Two replicas cannot be fed
 * different logs here, because nothing in this project decides what the log is.
 *
 * <h2>Why it is a function of a seed</h2>
 *
 * <p>Every decision — which command, which fault, which replica — comes from one {@link Random}
 * seeded once. The kernel reads no clock, allocates no identifier and consults no map whose
 * iteration order varies, so a failure at seed 8123 is the same failure at seed 8123 tomorrow, on
 * another machine, on another JDK. That property is the entire value of the exercise: without it a
 * chaos test finds a bug you cannot then reproduce.
 *
 * <p>Invariants are checked after <b>every step</b>. A registry that briefly holds two production
 * versions and then settles looks healthy by the time a run finishes.
 */
public final class Simulator {

    /**
     * A run's parameters.
     *
     * @param seed the one number the whole run is a function of
     * @param steps how many commands to generate
     * @param replicas how many replicas apply the log
     * @param flaws defects to reintroduce in the components around the kernel
     */
    public record Config(long seed, int steps, int replicas, Set<Flaw> flaws) {

        public Config {
            if (replicas < 1) {
                throw new IllegalArgumentException("a run needs at least one replica");
            }
            flaws = flaws == null || flaws.isEmpty()
                    ? EnumSet.noneOf(Flaw.class)
                    : EnumSet.copyOf(flaws);
        }

        /** Three replicas, 400 steps, nothing broken on purpose. */
        public static Config of(long seed) {
            return new Config(seed, 400, 3, EnumSet.noneOf(Flaw.class));
        }

        /** The same, with one component broken. */
        public Config with(Flaw flaw) {
            Set<Flaw> combined = EnumSet.copyOf(flaws.isEmpty()
                    ? EnumSet.noneOf(Flaw.class) : flaws);
            combined.add(flaw);
            return new Config(seed, steps, replicas, combined);
        }

        /** The same, for a different number of steps. */
        public Config steps(int newSteps) {
            return new Config(seed, newSteps, replicas, flaws);
        }
    }

    /**
     * What a run did, so a test can require that it was actually hostile.
     *
     * <p>Every one of these is asserted somewhere. A safety suite that passes because the registry
     * sat empty and nothing was injected is the failure mode this project is most exposed to, and
     * the only defence is to make the run describe itself.
     *
     * @param seed the seed
     * @param steps commands generated
     * @param checks invariant checks performed
     * @param accepted commands the kernel accepted and that changed something
     * @param idempotent commands accepted as retries
     * @param rejected commands refused, by code
     * @param replicaCrashes replicas rebuilt from a snapshot plus the log
     * @param powerLosses appends lost to a crash before their sync
     * @param checkpoints snapshots taken
     * @param prefixesReleased log prefixes discarded
     * @param duplicateDeliveries log entries applied to a replica twice
     * @param sinkRefusals deliveries the downstream world refused
     * @param dispatcherRestarts dispatchers replaced mid-run
     * @param effectsProduced effects the kernel produced
     * @param effectsApplied effects the downstream world applied
     * @param redeliveries redeliveries the consumer's watermark absorbed
     * @param collections artifacts collected
     * @param demotions incumbent production versions superseded
     * @param deliveriesLostBeforeAck crashes injected between a delivery and its acknowledgement
     * @param corruptUploadsRefused uploads whose bytes did not match the digest offered for them
     */
    public record Report(
            long seed,
            int steps,
            long checks,
            int accepted,
            int idempotent,
            Map<RejectionCode, Integer> rejected,
            int replicaCrashes,
            int powerLosses,
            int checkpoints,
            int prefixesReleased,
            int duplicateDeliveries,
            int sinkRefusals,
            int dispatcherRestarts,
            long effectsProduced,
            long effectsApplied,
            long redeliveries,
            int collections,
            int demotions,
            int deliveriesLostBeforeAck,
            int corruptUploadsRefused) {

        /** Total commands refused. */
        public int rejectedTotal() {
            int total = 0;
            for (int count : rejected.values()) {
                total += count;
            }
            return total;
        }

        /** How many distinct rejection codes the run reached. */
        public int rejectionCodesReached() {
            return rejected.size();
        }

        @Override
        public String toString() {
            return "seed=" + seed
                    + " steps=" + steps
                    + " checks=" + checks
                    + " accepted=" + accepted
                    + " idempotent=" + idempotent
                    + " rejected=" + rejectedTotal() + rejected
                    + " crashes=" + replicaCrashes
                    + " powerLosses=" + powerLosses
                    + " checkpoints=" + checkpoints
                    + " prefixesReleased=" + prefixesReleased
                    + " duplicateDeliveries=" + duplicateDeliveries
                    + " sinkRefusals=" + sinkRefusals
                    + " dispatcherRestarts=" + dispatcherRestarts
                    + " effects=" + effectsProduced + "/" + effectsApplied
                    + " redeliveries=" + redeliveries
                    + " collections=" + collections
                    + " demotions=" + demotions
                    + " deliveredThenDied=" + deliveriesLostBeforeAck
                    + " corruptUploads=" + corruptUploadsRefused;
        }
    }

    /** One replica: a kernel, and its own snapshots. */
    private static final class Replica {
        private final String name;
        private final InMemorySnapshotStore snapshots = new InMemorySnapshotStore();
        private PureKernel kernel = new PureKernel();

        /**
         * The snapshot store the replica actually uses, when it is a broken wrapper.
         *
         * <p>Null in a healthy run.
         */
        private SnapshotStore aliased;

        /**
         * The index this replica believes its newest snapshot covers.
         *
         * <p>Its own belief, recorded when it asked for a snapshot, rather than whatever the store
         * managed to write. That is faithful to a real node, which compacts its log on the strength
         * of the snapshot it thinks it took — and it is the difference between the
         * snapshot-aliasing defect being survivable and being data loss.
         */
        private long believedCheckpoint;

        Replica(String name) {
            this.name = name;
        }
    }

    private final Config config;
    private final Random faults;
    private final CommandGenerator generator;
    private final Invariants invariants;

    private final InMemoryCommandLog log = new InMemoryCommandLog();
    private final List<Replica> replicas = new ArrayList<>();
    private final Map<Digest, byte[]> pool = new HashMap<>();
    private final BlobStore blobs;
    private final World world;
    private final AppliedLedger ledger = new InMemoryAppliedLedger();
    private final EffectSink sink;
    private Dispatcher dispatcher;

    private int accepted;
    private int idempotent;
    private final Map<RejectionCode, Integer> rejected = new EnumMap<>(RejectionCode.class);
    private int replicaCrashes;
    private int powerLosses;
    private int checkpoints;
    private int prefixesReleased;
    private int duplicateDeliveries;
    private int sinkRefusals;
    private int dispatcherRestarts;
    private int collections;
    private int demotions;
    private int deliveriesLostBeforeAck;
    private int corruptUploadsRefused;

    public Simulator(Config config) {
        this.config = config;
        this.faults = new Random(config.seed() * 31 + 7);
        this.generator = new CommandGenerator(config.seed());
        this.invariants = new Invariants(config.seed());

        for (int i = 0; i < config.replicas(); i++) {
            replicas.add(new Replica("r" + i));
        }
        for (int i = 0; i < CommandGenerator.digestPoolSize(); i++) {
            pool.put(CommandGenerator.digest(i), CommandGenerator.contentFor(i));
        }

        BlobStore realBlobs = new InMemoryBlobStore();
        this.blobs = config.flaws().contains(Flaw.TRUST_CLIENT_DIGEST)
                ? FlawedComponents.blobStoreThatTrustsTheClient(realBlobs)
                : realBlobs;
        this.world = new World(blobs);
        this.sink = config.flaws().contains(Flaw.WATERMARK_BEFORE_DELIVERY)
                ? FlawedComponents.sinkThatRecordsBeforeDelivering(world, ledger)
                : new IdempotentSink(world, ledger);

        if (config.flaws().contains(Flaw.SNAPSHOT_ALIASES_LIVE_STATE)) {
            for (Replica replica : replicas) {
                replica.aliased = FlawedComponents.snapshotStoreThatAliasesLiveState(
                        replica.snapshots);
            }
        }
        this.dispatcher = newDispatcher();
    }

    /** Runs the simulation, throwing {@link InvariantViolation} on the first property that fails. */
    public Report run() {
        for (int step = 1; step <= config.steps(); step++) {
            injectFault(step);
            proposeGenerated(step);
            // Not on every step. A dispatcher runs on a timer rather than on every command, and
            // draining after each one keeps the outbox empty — which makes the depth metric
            // meaningless, and makes the crash-between-delivery-and-acknowledgement fault a no-op
            // because there is never anything in flight to lose.
            if (faults.nextInt(10) < 6) {
                drain();
            }
            check(step);
        }
        // One last drain with the sink healthy, so the end-state checks are about the design rather
        // than about a fault that happened to be active on the last step.
        world.recover();
        for (int i = 0; i < 4; i++) {
            drain();
        }
        check(config.steps());
        finalAgreement(config.steps());

        return new Report(
                config.seed(), config.steps(), invariants.checkCount(),
                accepted, idempotent, Map.copyOf(rejected),
                replicaCrashes, powerLosses, checkpoints, prefixesReleased,
                duplicateDeliveries, sinkRefusals, dispatcherRestarts,
                leader().view().nextEffectSeq() - 1, world.appliedCount(),
                absorbedRedeliveries(), collections, demotions, deliveriesLostBeforeAck,
                corruptUploadsRefused);
    }

    // ---- the step ----------------------------------------------------------------------------

    private void proposeGenerated(long step) {
        Command command = generator.next(step);

        if (command instanceof Command.IngestBlob ingest) {
            // The bytes are written and measured before the command is proposed, and the command
            // carries the measured length rather than the generated one. That ordering is the
            // server's contract too: nothing is recorded about an artifact until it is on disk and
            // its digest has been computed from what was actually written.
            long measured = storeArtifact(ingest.digest());
            if (measured < 0) {
                // The upload was refused because the bytes did not match the digest they were
                // offered under. Nothing is recorded, which is the point: the registry never learns
                // that this happened. Proposing the command anyway would be the defect.
                return;
            }
            boolean lieAboutTheLength = faults.nextInt(30) == 0;
            command = new Command.IngestBlob(
                    ingest.digest(), lieAboutTheLength ? measured + 1 : measured);
        }

        propose(command);
    }

    /** Appends a command, syncs, and applies it to every replica. */
    private Outcome propose(Command command) {
        log.append(command);
        log.sync();
        Outcome outcome = null;
        for (Replica replica : replicas) {
            Outcome applied = applyPending(replica);
            if (replica == leaderReplica()) {
                outcome = applied;
            }
        }
        if (outcome == null) {
            throw new IllegalStateException("the leader did not apply the command");
        }
        record(outcome);
        return outcome;
    }

    /**
     * Applies everything durable that a replica has not seen, checking rejection purity as it goes.
     *
     * @return the outcome of the last command applied, or a duplicate marker if there was none
     */
    private Outcome applyPending(Replica replica) {
        Outcome last = Outcome.Applied.duplicate();
        long durable = log.durableIndex();
        while (replica.kernel.registry().appliedIndex() < durable) {
            long index = replica.kernel.registry().appliedIndex() + 1;
            Command command = log.read(index, 1).get(0).command();
            RegistryView before = replica.kernel.registry();
            last = replica.kernel.apply(index, command);
            if (!last.accepted()) {
                invariants.rejectionPurity(index, replica.name, before, replica.kernel.registry());
            }
        }
        return last;
    }

    private void drain() {
        Dispatcher.Run run = dispatcher.drainAll();
        if (run.failure() != null) {
            sinkRefusals++;
        }
    }

    private void check(long step) {
        Map<String, RegistryView> views = new LinkedHashMap<>();
        for (Replica replica : replicas) {
            invariants.check(step, replica.name, replica.kernel.view());
            views.put(replica.name, replica.kernel.view());
        }
        invariants.convergence(step, views);
        invariants.exactlyOnceDelivery(
                step, world.appliedEffects(), leader().view().dispatchedThrough());
        invariants.artifactIntegrity(step, leader().view(), this::digestOfStoredBytes);
    }

    /**
     * I12: once every effect has been delivered, the downstream world agrees with the registry
     * about which version of each model is in production.
     *
     * <p>The property an operator actually cares about, and the one that catches an ordering
     * mistake no state invariant can see. A registry can be internally perfect and still have told
     * a serving tier to load a version it has since retired, if the effects describing the change
     * arrived in the wrong order or one of them was dropped.
     */
    private void finalAgreement(long step) {
        if (!leader().view().outbox().isEmpty()) {
            // Delivery did not finish, which is a legitimate end state if the sink was refusing.
            // Asserting agreement here would be asserting something the design does not promise.
            return;
        }
        Map<ModelId, VersionId> expected = new HashMap<>();
        leader().view().models().forEach((id, model) ->
                model.production().ifPresent(version -> expected.put(id, version.version())));
        invariants.downstreamAgreement(step, expected, world.productionBeliefs());
    }

    // ---- faults ------------------------------------------------------------------------------

    private void injectFault(long step) {
        int choice = faults.nextInt(100);
        if (choice < 3) {
            crashReplica();
        } else if (choice < 5) {
            powerLossBeforeSync(step);
        } else if (choice < 9) {
            checkpointReplica();
        } else if (choice < 11) {
            deliverTwice();
        } else if (choice < 14) {
            world.failFrom(leader().view().dispatchedThrough() + 1 + faults.nextInt(3));
        } else if (choice < 18) {
            world.recover();
        } else if (choice < 20) {
            dispatcher = newDispatcher();
            dispatcherRestarts++;
        } else if (choice < 22) {
            staleAcknowledgement();
        } else if (choice < 24) {
            acknowledgementAheadOfTheLog();
        } else if (choice < 36) {
            deliveredThenDied();
        }
    }

    /**
     * The crash window this entire design exists for: effects delivered, process dead before the
     * acknowledgement was committed.
     *
     * <p>Delivers a few effects straight to the sink and then stops, leaving the registry still
     * owing them. The next drain offers them again, and the consumer's watermark has to absorb the
     * repeat — which is the half of exactly-once that the registry cannot supply on its own.
     *
     * <p>Without this fault the simulation never produced a single redelivery, because
     * acknowledgements in it are always durable. A suite that reports zero duplicates is not
     * reporting that duplicates are handled; it is reporting that none happened.
     */
    private void deliveredThenDied() {
        List<SequencedEffect> pending = leader().view().outbox();
        if (pending.isEmpty()) {
            return;
        }
        int count = Math.min(pending.size(), 1 + faults.nextInt(3));
        int landed = 0;
        for (int i = 0; i < count; i++) {
            try {
                sink.deliver(pending.get(i));
                landed++;
            } catch (RuntimeException refused) {
                // The world was refusing anyway. Nothing is acknowledged either way, but nothing
                // landed either, so this was not the fault it was meant to be and is not counted
                // as one: an inflated fault count is worse than a missing one, because a suite
                // that over-reports its own hostility cannot be used to argue anything.
                break;
            }
        }
        if (landed > 0) {
            deliveriesLostBeforeAck++;
        }
    }

    /**
     * A dispatcher from a previous life acknowledges something already acknowledged.
     *
     * <p>Must be an accepted no-op: the watermark only moves forward, and a redelivered
     * acknowledgement is the normal consequence of the crash window this design tolerates.
     */
    private void staleAcknowledgement() {
        long watermark = leader().view().dispatchedThrough();
        if (watermark == 0) {
            return;
        }
        propose(new Command.AckEffects(Math.max(0, watermark - 1 - faults.nextInt(3))));
    }

    /**
     * A confused dispatcher acknowledges effects the registry has never produced.
     *
     * <p>Must be refused. Clamping it would hide the only symptom of a dispatcher talking to a
     * registry that is behind it — two writers, or a restore from an older snapshot.
     *
     * <p>Note which acknowledgement is <i>not</i> injected: one in between, above the watermark and
     * below the next sequence number, which would skip effects nobody had delivered. The kernel
     * accepts that, correctly, because it cannot tell such an acknowledgement from a real one —
     * which is exactly why {@code AckEffects} is a privileged command and why nothing but the
     * dispatcher may propose it.
     */
    private void acknowledgementAheadOfTheLog() {
        propose(new Command.AckEffects(
                leader().view().nextEffectSeq() + faults.nextInt(5)));
    }

    /**
     * A replica loses everything in memory and rebuilds from its own snapshot plus the log.
     *
     * <p>The interesting part is that this must be invisible. The replica comes back with the same
     * state it had, which is what I1 and I8 assert, and the path it takes to get there —
     * possibly replaying hundreds of records, possibly replaying records its snapshot already
     * covered — is the one that has to be idempotent.
     */
    private void crashReplica() {
        Replica replica = replicas.get(faults.nextInt(replicas.size()));
        SnapshotStore store = replica.aliased != null ? replica.aliased : replica.snapshots;
        replica.kernel = Recovery.open(log, store).kernel();
        applyPending(replica);
        replicaCrashes++;
    }

    /**
     * A command reaches the log and the power fails before the sync.
     *
     * <p>Nothing acknowledged it and no replica applied it, so losing it is correct. The assertion
     * is that the rest of the system does not notice — in particular that no replica's applied
     * index goes backwards, which it would if the simulation had let one apply an undurable record.
     */
    private void powerLossBeforeSync(long step) {
        log.append(generator.next(step));
        log.crash();
        powerLosses++;
    }

    /** A replica snapshots, and the log releases whatever every replica has now covered. */
    private void checkpointReplica() {
        Replica replica = replicas.get(faults.nextInt(replicas.size()));
        SnapshotStore store = replica.aliased != null ? replica.aliased : replica.snapshots;
        long believed = replica.kernel.registry().appliedIndex();
        store.save(replica.kernel.registry());
        replica.believedCheckpoint = believed;
        checkpoints++;

        // The prefix can only go once every replica could rebuild without it. That is what a real
        // deployment's retention policy is, and getting it wrong here would make a later crash
        // unrecoverable for reasons that have nothing to do with the code under test.
        //
        // Note which number this uses: what each replica *believes* it snapshotted, not what its
        // store actually wrote. A node compacts on the strength of its own belief, and a store that
        // quietly wrote something older is exactly the defect under test.
        long safe = Long.MAX_VALUE;
        for (Replica other : replicas) {
            safe = Math.min(safe, other.believedCheckpoint);
        }
        if (safe > 0 && safe > log.firstIndex() - 1) {
            log.discardThrough(safe);
            prefixesReleased++;
        }
    }

    /**
     * A log entry is applied to a replica twice.
     *
     * <p>What a driver does after a crash, or when a restore overlaps the log. The kernel is
     * idempotent in the log index precisely so that no driver has to be careful, and this is the
     * fault that proves it.
     */
    private void deliverTwice() {
        Replica replica = replicas.get(faults.nextInt(replicas.size()));
        long index = replica.kernel.registry().appliedIndex();
        if (index < log.firstIndex()) {
            return;
        }
        Command command = log.read(index, 1).get(0).command();
        Outcome outcome = replica.kernel.apply(index, command);
        if (!(outcome instanceof Outcome.Applied applied)
                || applied.kind() != Outcome.Applied.Kind.DUPLICATE) {
            throw new InvariantViolation("I8 Monotonicity", config.seed(), index,
                    "replica " + replica.name + " did not treat index " + index
                            + " as already applied: " + outcome);
        }
        duplicateDeliveries++;
    }

    // ---- the downstream world ----------------------------------------------------------------

    /**
     * Everything outside the registry: a collector that deletes artifacts, and a serving tier that
     * remembers which version it was told to load.
     *
     * <p>Both are real consumers rather than counters. The collector actually removes the bytes,
     * so an artifact the registry still claims is present but whose bytes are gone fails I11; and
     * the serving tier's belief is compared against the registry in I12.
     */
    private final class World implements EffectSink {

        private final BlobStore artifacts;
        private final List<SequencedEffect> applied = new ArrayList<>();
        private final Map<ModelId, VersionId> production = new HashMap<>();
        private long failFrom = Long.MAX_VALUE;
        private long redeliveries;

        World(BlobStore artifacts) {
            this.artifacts = artifacts;
        }

        @Override
        public void deliver(SequencedEffect effect) {
            if (effect.seq() >= failFrom) {
                throw new IllegalStateException("the downstream world refused #" + effect.seq());
            }
            if (!applied.isEmpty() && effect.seq() <= applied.get(applied.size() - 1).seq()) {
                // Reached only when the consumer's deduplication is broken; the idempotent sink
                // normally filters these before they arrive. Counted rather than rejected so I7
                // is the thing that fails, with its own message.
                redeliveries++;
            }
            applied.add(effect);
            switch (effect.effect()) {
                case Effect.ArtifactCollected collected -> {
                    artifacts.delete(collected.digest());
                    collections++;
                }
                case Effect.ProductionChanged changed -> {
                    if (changed.production() == null) {
                        production.remove(changed.model());
                    } else {
                        production.put(changed.model(), changed.production());
                    }
                }
                case Effect.StageChanged changed -> {
                    if (changed.from() == io.cairn.core.Stage.PRODUCTION) {
                        demotions++;
                    }
                }
                default -> { }
            }
        }

        List<SequencedEffect> appliedEffects() {
            return List.copyOf(applied);
        }

        Map<ModelId, VersionId> productionBeliefs() {
            return Map.copyOf(production);
        }

        long appliedCount() {
            return applied.size();
        }

        long redeliveries() {
            return redeliveries;
        }

        void failFrom(long seq) {
            failFrom = seq;
        }

        void recover() {
            failFrom = Long.MAX_VALUE;
        }

        @Override
        public String name() {
            return "world";
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    /**
     * How many redeliveries the consumer's watermark discarded.
     *
     * <p>Reported so a test can require that the crash window was actually reached. Zero here with
     * {@code deliveredThenDied > 0} would mean the deduplication never ran.
     */
    private long absorbedRedeliveries() {
        return sink instanceof IdempotentSink idempotent
                ? idempotent.duplicateCount()
                : world.redeliveries();
    }

    private Dispatcher newDispatcher() {
        return new Dispatcher(() -> leader().view(), sink, this::propose);
    }

    private Replica leaderReplica() {
        return replicas.get(0);
    }

    private PureKernel leader() {
        return leaderReplica().kernel;
    }

    /**
     * Writes an artifact, sometimes offering the wrong bytes for it.
     *
     * <p>A corrupt upload — a truncated transfer, a mangled proxy, a client that computed its
     * digest over something else — is an ordinary event, and the correct response is for the blob
     * store to refuse it and for nothing to be recorded. Injecting it here does two jobs: it
     * exercises that refusal on every healthy run, and it is what makes
     * {@link Flaw#TRUST_CLIENT_DIGEST} bite, because a store that never gets lied to has nothing
     * to be caught believing.
     *
     * @return the measured length, or -1 if the upload was refused
     */
    private long storeArtifact(Digest digest) {
        byte[] content = pool.get(digest);
        if (content == null) {
            throw new IllegalStateException("the generator produced an unknown digest " + digest);
        }
        byte[] offered = content;
        if (faults.nextInt(12) == 0) {
            offered = content.clone();
            offered[faults.nextInt(offered.length)] ^= 0x40;
        }
        try (InputStream bytes = new ByteArrayInputStream(offered)) {
            return blobs.putVerified(digest, bytes).size();
        } catch (io.cairn.store.DigestMismatchException refused) {
            corruptUploadsRefused++;
            return -1;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Digest digestOfStoredBytes(Digest claimed) {
        if (!blobs.contains(claimed)) {
            return null;
        }
        try (InputStream bytes = blobs.open(claimed)) {
            return Digest.ofSha256(MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void record(Outcome outcome) {
        switch (outcome) {
            case Outcome.Applied applied -> {
                if (applied.kind() == Outcome.Applied.Kind.NEW) {
                    accepted++;
                } else {
                    idempotent++;
                }
            }
            case Outcome.Rejected refused ->
                    rejected.merge(refused.code(), 1, Integer::sum);
        }
    }
}
