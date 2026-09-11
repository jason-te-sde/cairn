package io.cairn.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.cairn.core.Command;
import io.cairn.core.PureKernel;
import io.cairn.core.SequencedEffect;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Proof that the suite can go red.
 *
 * <p>Each test here reintroduces one defect from {@link Flaw} and requires the check named in
 * {@link Flaw#caughtBy()} to be the one that fails. That mapping is asserted rather than
 * documented, so a check that stops working fails at the flaw it was supposed to catch instead of
 * passing quietly alongside everything else.
 *
 * <p>Five of these are defects the system this project is derived from actually shipped. The
 * remaining three are mistakes a competent implementation of these rules could plausibly make. The
 * distinction is recorded on the enum and asserted at the bottom of this file, because "we put back
 * a real bug" and "we invented a bug we would obviously never write" are very different claims
 * about a test suite.
 *
 * <p>Each flaw is hunted across a sweep of seeds rather than pinned to one. The sweep is
 * deterministic, so the test is too; what it buys is that a flaw which shows up on most seeds but
 * not all does not make this file a collection of magic numbers that break whenever a fault rate
 * changes.
 */
@org.junit.jupiter.api.TestMethodOrder(
        org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class FlawTest {

    private static final int SEEDS = 30;
    private static final int STEPS = 300;

    /** Flaws this file has a test for, checked against the enum at the end. */
    private static final Set<Flaw> covered = new HashSet<>();

    /**
     * Runs a sweep and returns the first failure the predicate accepts.
     *
     * <p>Matched by a predicate rather than by exception type, because the flaws are not all caught
     * the same way and pretending otherwise would be the test shaping the finding. Two are caught by
     * an invariant firing, one by a record refusing to be constructed, one by recovery refusing to
     * start. Where a defect is caught is part of what the suite has to say about it.
     */
    private static Throwable expectCaught(
            Flaw flaw, SweepStep step, java.util.function.Predicate<Throwable> matches) {
        List<String> otherFailures = new ArrayList<>();
        for (long seed = 1; seed <= SEEDS; seed++) {
            try {
                step.run(seed);
            } catch (RuntimeException | AssertionError failure) {
                // Both, because the two ways a defect surfaces here are not the same Java type:
                // InvariantViolation is an AssertionError, and a store or a record refusing
                // something is a RuntimeException.
                if (matches.test(failure)) {
                    covered.add(flaw);
                    return failure;
                }
                otherFailures.add("seed " + seed + ": "
                        + failure.getClass().getSimpleName() + " " + failure.getMessage());
            }
        }
        return fail(flaw + " was not caught by " + flaw.caughtBy() + " in " + SEEDS
                + " seeds. Other failures seen: " + otherFailures);
    }

    /** The common case: an invariant with this name fired. */
    private static InvariantViolation expectInvariant(Flaw flaw, String invariant, SweepStep step) {
        return (InvariantViolation) expectCaught(flaw, step,
                failure -> failure instanceof InvariantViolation violation
                        && violation.invariant().equals(invariant));
    }

    @FunctionalInterface
    private interface SweepStep {
        void run(long seed);
    }

    // ---- the five inherited defects -----------------------------------------------------------

    @Test
    @org.junit.jupiter.api.Order(1)
    void effectsPublishedFromInsideApplyAreDeliveredTwiceAfterAReplay() {
        // The defect this whole project is organized around. The original published to Kafka inside
        // its state machine's apply method, so a replica replaying its log after a restart
        // republished everything it had ever published.
        Flaw flaw = Flaw.EFFECTS_IN_APPLY;
        InvariantViolation caught = expectInvariant(
                flaw, "I7 Exactly-once delivery", seed -> {
            List<SequencedEffect> world = new ArrayList<>();
            List<Command> script = new ArrayList<>();
            CommandGenerator generator = new CommandGenerator(seed);
            for (int step = 1; step <= STEPS; step++) {
                script.add(generator.next(step));
            }

            ReferenceKernel live = new ReferenceKernel(EnumSet.of(flaw), world::add);
            for (int i = 0; i < script.size(); i++) {
                live.apply(i + 1, script.get(i));
            }
            int beforeReplay = world.size();
            assertTrue(beforeReplay > 0, "seed " + seed + " produced no effects to lose");

            // The restart. A replica rebuilds by replaying its log, which with this defect means
            // publishing everything again.
            ReferenceKernel restarted = new ReferenceKernel(EnumSet.of(flaw), world::add);
            for (int i = 0; i < script.size(); i++) {
                restarted.apply(i + 1, script.get(i));
            }

            new Invariants(seed).exactlyOnceDelivery(script.size(), world, 0);
        });
        assertTrue(caught.getMessage().contains("more than once"), caught.getMessage());
        assertTrue(flaw.inherited());
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void anApplyErrorSwallowedAndLoggedMakesReplicasDiverge() {
        // The original wrapped its apply method in catch (Exception e) { LOG.error(...) }. A replica
        // that hits the error path skips the mutation, stays in the cluster, and looks healthy.
        Flaw flaw = Flaw.SWALLOW_APPLY_ERRORS;
        InvariantViolation caught = expectInvariant(flaw, "I1 Convergence", seed ->
                DifferentialRunner.run(seed, STEPS, new PureKernel(),
                        new ReferenceKernel(EnumSet.of(flaw), effect -> { })));
        assertNotNull(caught.getMessage());
        assertTrue(flaw.inherited());
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void aRestoreThatAliasesTheSnapshotLosesEveryWriteSinceIt() {
        // The original's readSnap closed the live RocksDB and reopened it on the checkpoint
        // directory, so writes after a restore landed inside the snapshot and vanished at the next.
        Flaw flaw = Flaw.SNAPSHOT_ALIASES_LIVE_STATE;
        // Caught by recovery refusing to start, not by an invariant. On its own the defect is
        // survivable: a snapshot that under-reports its index costs a longer replay. It becomes
        // data loss only once the log is compacted on the strength of that snapshot, and then the
        // right answer is to refuse to serve a registry with a hole in it. The first version of
        // this test asserted I8 Monotonicity and never fired, because the state never went
        // backwards — the log still had the records.
        Throwable caught = expectCaught(flaw,
                seed -> new Simulator(Simulator.Config.of(seed).steps(STEPS).with(flaw)).run(),
                failure -> failure instanceof io.cairn.store.StoreException
                        && failure.getMessage().contains("acknowledged are missing"));
        assertNotNull(caught.getMessage());
        assertTrue(flaw.inherited());
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void aBlobStoreThatBelievesTheClientStoresBytesUnderTheWrongName() {
        // The original took a fileHash from the request and stored it, having never hashed the file.
        Flaw flaw = Flaw.TRUST_CLIENT_DIGEST;
        InvariantViolation caught = expectInvariant(flaw, "I11 Artifact integrity", seed ->
                new Simulator(Simulator.Config.of(seed).steps(STEPS).with(flaw)).run());
        assertTrue(caught.getMessage().contains("the digest is a lie")
                        || caught.getMessage().contains("nothing under that digest"),
                caught.getMessage());
        assertTrue(flaw.inherited());
    }

    // ---- the three plausible mistakes ---------------------------------------------------------

    @Test
    @org.junit.jupiter.api.Order(5)
    void aRepublishThatOverwritesBreaksTheOnlyGuaranteeAVersionMakes() {
        Flaw flaw = Flaw.OVERWRITE_ON_REPUBLISH;
        InvariantViolation caught = expectInvariant(flaw, "I2 Version immutability", seed ->
                KernelRunner.run(seed, STEPS, new ReferenceKernel(EnumSet.of(flaw), e -> { })));
        assertTrue(caught.getMessage().contains("more than one artifact"), caught.getMessage());
        assertEquals("I2 Version immutability", caught.invariant());
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    void collectingWithoutCountingProducesAStateThatCannotBeRepresented() {
        // Caught by Blob's constructor rather than by an invariant, which is the better outcome: it
        // fails at the line that caused it, with a stack trace naming the caller.
        Flaw flaw = Flaw.COLLECT_WITHOUT_COUNTING;
        List<String> messages = new ArrayList<>();
        for (long seed = 1; seed <= SEEDS; seed++) {
            try {
                KernelRunner.run(seed, STEPS, new ReferenceKernel(EnumSet.of(flaw), e -> { }));
            } catch (IllegalArgumentException refused) {
                if (refused.getMessage() != null
                        && refused.getMessage().contains("still referenced")) {
                    covered.add(flaw);
                    messages.add(refused.getMessage());
                    break;
                }
            } catch (InvariantViolation violation) {
                messages.add("invariant " + violation.invariant());
            }
        }
        assertTrue(covered.contains(flaw),
                flaw + " was not caught in " + SEEDS + " seeds; saw " + messages);
        assertTrue(messages.get(messages.size() - 1).contains("still referenced"),
                messages.toString());
    }

    @Test
    @org.junit.jupiter.api.Order(7)
    void aConsumerThatRecordsBeforeDeliveringLosesEffectsSilently() {
        Flaw flaw = Flaw.WATERMARK_BEFORE_DELIVERY;
        InvariantViolation caught = expectInvariant(flaw, "I7 Exactly-once delivery", seed ->
                new Simulator(Simulator.Config.of(seed).steps(STEPS).with(flaw)).run());
        assertTrue(caught.getMessage().contains("never applied downstream")
                        || caught.getMessage().contains("more than once")
                        || caught.getMessage().contains("must follow the sequence"),
                caught.getMessage());
        assertEquals("I7 Exactly-once delivery", caught.invariant());
    }

    // ---- the meta-assertions ------------------------------------------------------------------

    @Test
    @org.junit.jupiter.api.Order(8)
    void aHealthyRunOfTheSameSweepPassesCompletely() {
        // Without this, every test above could be passing because the sweep fails for some reason
        // unrelated to the flaw. This is the control.
        for (long seed = 1; seed <= 5; seed++) {
            KernelRunner.run(seed, STEPS, new ReferenceKernel());
            DifferentialRunner.run(seed, STEPS, new PureKernel(), new ReferenceKernel());
            new Simulator(Simulator.Config.of(seed).steps(STEPS)).run();
        }
    }

    @Test
    @org.junit.jupiter.api.Order(9)
    void everyFlawHasATest() {
        // The assertion that keeps this file honest as the enum grows. Adding a Flaw without
        // adding a test for it fails here, which is the only way a list of known mistakes stays a
        // list of *caught* mistakes.
        //
        // COLON_CODEC is covered by ColonCodecTest, which demonstrates the failure directly rather
        // than through an invariant, because the defect corrupts a record before any state exists
        // to have a property about.
        Set<Flaw> expected = EnumSet.allOf(Flaw.class);
        expected.remove(Flaw.COLON_CODEC);

        Set<Flaw> missing = EnumSet.copyOf(expected);
        missing.removeAll(covered);
        assertTrue(missing.isEmpty(),
                "these flaws have no test that proves they are caught: " + missing
                        + " (covered: " + covered + ")");
    }

    @Test
    @org.junit.jupiter.api.Order(10)
    void theInheritedFlawsAreTheOnesTheOriginalActuallyHad() {
        // Recorded as a test so the claim in the README cannot drift from the enum.
        Set<Flaw> inherited = EnumSet.noneOf(Flaw.class);
        for (Flaw flaw : Flaw.values()) {
            if (flaw.inherited()) {
                inherited.add(flaw);
            }
        }
        assertEquals(
                EnumSet.of(
                        Flaw.COLON_CODEC,
                        Flaw.EFFECTS_IN_APPLY,
                        Flaw.SWALLOW_APPLY_ERRORS,
                        Flaw.SNAPSHOT_ALIASES_LIVE_STATE,
                        Flaw.TRUST_CLIENT_DIGEST),
                inherited);
        assertEquals(8, Flaw.values().length);
    }
}
