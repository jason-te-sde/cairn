package io.cairn.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The simulation, on a fixed set of seeds that run on every build.
 *
 * <p>Each seed asserts two separate things, and the second one is the one that matters. The first
 * is that no invariant was violated, which is what the simulator throws on. The second is that the
 * run was actually hostile — that commands were accepted and refused, that replicas crashed and
 * rebuilt, that snapshots were taken and log prefixes released, that entries were delivered twice,
 * that the downstream world refused deliveries, that artifacts were collected and production
 * versions superseded. A safety suite that passes because the registry sat empty is the failure
 * mode this project is most exposed to, and the only defence is to make the run describe itself
 * and then check the description.
 *
 * <p>The floors below are set under the lowest value these seeds actually produce, not at it. A
 * floor set at the observed value is a test that fails when somebody changes a fault rate for a
 * good reason; a floor set far under it is a test that stops noticing. {@code SoakTest} carries the
 * rarer events, aggregated across many seeds, because some faults do not fire in every run and
 * requiring them per seed would be requiring a coincidence.
 */
class SimTest {

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 5, 7, 11, 42, 1337, 8123, 20260911})
    void everyPropertyHoldsUnderEveryFault(long seed) {
        Simulator.Report report = new Simulator(Simulator.Config.of(seed)).run();

        assertTrue(report.checks() > 1500,
                report + " performed only " + report.checks() + " checks");

        // The registry was used.
        assertTrue(report.accepted() > 50, report + " accepted almost nothing");
        assertTrue(report.idempotent() > 30, report + " never saw a retry");
        assertTrue(report.rejectedTotal() > 100, report + " never refused anything");
        assertTrue(report.rejectionCodesReached() >= 9,
                report + " only reached " + report.rejectionCodesReached()
                        + " of the rejection codes");

        // The faults fired.
        assertTrue(report.replicaCrashes() > 3, report + " barely crashed a replica");
        assertTrue(report.powerLosses() > 0, report + " never lost an unsynced append");
        assertTrue(report.checkpoints() > 3, report + " barely snapshotted");
        assertTrue(report.duplicateDeliveries() > 0, report + " never delivered an entry twice");
        assertTrue(report.sinkRefusals() > 5, report + " never had a delivery refused");
        assertTrue(report.dispatcherRestarts() > 0, report + " never restarted the dispatcher");

        // The interesting paths were reached.
        assertTrue(report.effectsProduced() > 20, report + " produced almost no effects");
        assertTrue(report.collections() > 0, report + " never collected an artifact");
        // Superseding a production version is not asserted here. It needs two versions of one
        // model to both reach PRODUCTION, which some seeds never arrange; requiring it per seed
        // would be requiring a coincidence. SoakTest asserts it across the sweep, where it is a
        // certainty rather than a hope.

        // And delivery caught up in the end, which is the claim the whole outbox exists to make.
        assertEquals(report.effectsProduced(), report.effectsApplied(),
                report + " ended with effects the downstream world never saw");
    }

    @Test
    void oneSeedIsOneRunForever() {
        // The property the whole exercise rests on. Two runs of the same seed must agree on every
        // number, or a failure found at a seed cannot be reproduced by replaying it.
        Simulator.Report first = new Simulator(Simulator.Config.of(8123)).run();
        Simulator.Report second = new Simulator(Simulator.Config.of(8123)).run();
        assertEquals(first, second, "the simulation is not a function of its seed");
    }

    @Test
    void differentSeedsAreDifferentRuns() {
        // The other half: a "deterministic" simulator that ignores its seed would satisfy the test
        // above perfectly.
        Simulator.Report one = new Simulator(Simulator.Config.of(1)).run();
        Simulator.Report two = new Simulator(Simulator.Config.of(2)).run();
        assertTrue(one.accepted() != two.accepted()
                        || one.rejectedTotal() != two.rejectedTotal()
                        || one.replicaCrashes() != two.replicaCrashes(),
                "two seeds produced identical runs: " + one);
    }

    @Test
    void aSingleReplicaIsAlsoARegistry() {
        // Convergence is vacuous with one replica, and everything else still has to hold. Worth a
        // test because a single node is how most people will run this.
        Simulator.Report report =
                new Simulator(new Simulator.Config(42, 300, 1, java.util.Set.of())).run();
        assertTrue(report.accepted() > 30, report.toString());
        assertEquals(report.effectsProduced(), report.effectsApplied(), report.toString());
    }

    @Test
    void fiveReplicasConvergeToo() {
        Simulator.Report report =
                new Simulator(new Simulator.Config(42, 300, 5, java.util.Set.of())).run();
        assertTrue(report.checks() > 1500, report.toString());
        assertTrue(report.replicaCrashes() > 0, report.toString());
    }
}
