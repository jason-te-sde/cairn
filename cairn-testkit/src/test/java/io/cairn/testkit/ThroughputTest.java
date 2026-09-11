package io.cairn.testkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cairn.codec.Codec;
import io.cairn.core.Command;
import io.cairn.core.PureKernel;
import io.cairn.core.Registry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * How fast the pure parts are, measured.
 *
 * <p>Off by default; enable with {@code -Dcairn.bench=true}.
 *
 * <p>The kernel number is the one that justifies the design. Every command copies the maps on the
 * path it touches, which is the obvious objection to building a state machine out of immutable
 * values — so the objection is answered with a number rather than an argument. The simulation
 * number is what decides how wide a soak sweep can be in a nightly job.
 *
 * <pre>
 * mvn test -pl cairn-testkit -am -Dcairn.bench=true -Dtest=ThroughputTest \
 *     -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@EnabledIfSystemProperty(named = "cairn.bench", matches = "true")
class ThroughputTest {

    @Test
    void kernelThroughput() {
        // A realistic mix, generated once so the measurement is of apply rather than of generation.
        List<Command> script = new ArrayList<>();
        CommandGenerator generator = new CommandGenerator(20260911L);
        for (int i = 1; i <= 200_000; i++) {
            script.add(generator.next(i));
        }

        PureKernel warmup = new PureKernel();
        for (int i = 0; i < 20_000; i++) {
            warmup.apply(i + 1, script.get(i));
        }

        PureKernel kernel = new PureKernel();
        long start = System.nanoTime();
        for (int i = 0; i < script.size(); i++) {
            kernel.apply(i + 1, script.get(i));
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("kernel          %,10.0f commands/s  (%,d in %.3fs, final state %s)%n",
                script.size() / seconds, script.size(), seconds,
                Codec.stateFingerprint(kernel.registry()));
        assertTrue(script.size() / seconds > 1000, "under a thousand commands a second");
    }

    @Test
    void simulationThroughput() {
        new Simulator(Simulator.Config.of(1).steps(200)).run();

        long steps = 0;
        long checks = 0;
        long start = System.nanoTime();
        for (long seed = 1; seed <= 40; seed++) {
            Simulator.Report report = new Simulator(Simulator.Config.of(seed)).run();
            steps += report.steps();
            checks += report.checks();
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("simulation      %,10.0f steps/s  %,10.0f checks/s  (%,d checks"
                        + " in %.3fs)%n",
                steps / seconds, checks / seconds, checks, seconds);
        assertTrue(steps / seconds > 10);
    }

    @Test
    void codecThroughputAndSnapshotSize() {
        PureKernel kernel = new PureKernel();
        CommandGenerator generator = new CommandGenerator(7L);
        for (int i = 1; i <= 20_000; i++) {
            kernel.apply(i, generator.next(i));
        }
        Registry state = kernel.registry();

        byte[] snapshot = Codec.encodeSnapshot(state);
        int versions = 0;
        for (var model : state.models().values()) {
            versions += model.versions().size();
        }

        for (int i = 0; i < 200; i++) {
            Codec.decodeSnapshot(snapshot);
        }
        long start = System.nanoTime();
        int rounds = 2_000;
        for (int i = 0; i < rounds; i++) {
            Codec.decodeSnapshot(Codec.encodeSnapshot(state));
        }
        double seconds = (System.nanoTime() - start) / 1e9;

        System.out.printf(
                "snapshot        %,10.0f round-trips/s  %,d bytes for %d models / %d versions"
                        + " / %d artifacts%n",
                rounds / seconds, snapshot.length, state.models().size(), versions,
                state.blobs().size());
        assertTrue(rounds / seconds > 10);
    }
}
