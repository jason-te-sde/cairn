package io.cairn.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import io.cairn.core.RejectionCode;
import org.junit.jupiter.api.Test;

/**
 * A sweep over many seeds, for the faults that do not fire in every run.
 *
 * <p>Two reasons this exists separately from {@link SimTest}. Some events — a crash landing exactly
 * between a delivery and its acknowledgement, a run that reaches every rejection code — happen in a
 * minority of runs, and requiring them per seed would be requiring a coincidence; aggregated over a
 * sweep they are certainties, and asserting them there is the difference between hoping the suite
 * covers a path and knowing it does. And a wider sweep is how the rare interleavings get found at
 * all.
 *
 * <pre>
 * mvn install -DskipTests
 * mvn test -pl cairn-testkit -am -Dtest=SoakTest -Dcairn.sim.seeds=2000 \
 *     -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>The default is small enough to run on every build. The nightly workflow runs it wide.
 */
class SoakTest {

    private static final int DEFAULT_SEEDS = 60;

    @Test
    void aSweepOfSeedsViolatesNothingAndCoversEverything() {
        int seeds = Integer.getInteger("cairn.sim.seeds", DEFAULT_SEEDS);

        long checks = 0;
        long steps = 0;
        long effects = 0;
        long redeliveries = 0;
        int crashes = 0;
        int powerLosses = 0;
        int checkpoints = 0;
        int prefixesReleased = 0;
        int duplicates = 0;
        int deliveredThenDied = 0;
        int collections = 0;
        int demotions = 0;
        Map<RejectionCode, Integer> rejections = new EnumMap<>(RejectionCode.class);

        for (long seed = 1; seed <= seeds; seed++) {
            Simulator.Report report = new Simulator(Simulator.Config.of(seed)).run();
            checks += report.checks();
            steps += report.steps();
            effects += report.effectsProduced();
            redeliveries += report.redeliveries();
            crashes += report.replicaCrashes();
            powerLosses += report.powerLosses();
            checkpoints += report.checkpoints();
            prefixesReleased += report.prefixesReleased();
            duplicates += report.duplicateDeliveries();
            deliveredThenDied += report.deliveriesLostBeforeAck();
            collections += report.collections();
            demotions += report.demotions();
            report.rejected().forEach((code, count) -> rejections.merge(code, count, Integer::sum));

            assertEquals(report.effectsProduced(), report.effectsApplied(),
                    "seed " + seed + " ended owing effects: " + report);
        }

        String summary = seeds + " seeds: " + steps + " steps, " + checks + " checks, "
                + effects + " effects, " + crashes + " crashes, " + powerLosses + " power losses, "
                + checkpoints + " checkpoints, " + prefixesReleased + " prefixes released, "
                + duplicates + " duplicate deliveries, " + deliveredThenDied
                + " crashes between delivery and ack, " + redeliveries + " redeliveries absorbed, "
                + collections + " collections, " + demotions + " demotions, rejections "
                + new TreeMap<>(rejections);
        System.out.println(summary);

        assertTrue(checks > seeds * 1500L, summary);
        assertTrue(crashes > seeds, summary);
        assertTrue(powerLosses > 0, summary);
        assertTrue(checkpoints > seeds, summary);
        assertTrue(prefixesReleased > 0, summary);
        assertTrue(duplicates > 0, summary);
        assertTrue(collections > seeds, summary);
        assertTrue(demotions > 0, summary);

        // The crash window the whole design exists for, and the deduplication that absorbs it.
        // Zero here would mean exactly-once delivery is asserted on runs where nothing was ever
        // delivered twice, which is not an assertion about exactly-once at all.
        assertTrue(deliveredThenDied > 0, summary);
        assertTrue(redeliveries > 0, summary);

        // And the sweep reached every way a command can be refused. This is the assertion that
        // keeps the generator honest as the rules grow: adding a rejection code without adding a
        // way to reach it fails here.
        for (RejectionCode code : RejectionCode.values()) {
            assertTrue(rejections.containsKey(code),
                    "no seed in the sweep reached " + code + "; " + summary);
        }
    }
}
