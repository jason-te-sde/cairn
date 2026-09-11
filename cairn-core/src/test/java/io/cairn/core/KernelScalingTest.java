package io.cairn.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * What immutability costs, as a curve rather than a claim.
 *
 * <p>Off by default; enable with {@code -Dcairn.bench=true}.
 *
 * <p>{@link Model} holds its versions in a sorted map and every publish returns a new model, so
 * adding a version to a model that already has <i>N</i> of them copies <i>N</i> entries. Publishing
 * <i>N</i> versions into one model is therefore O(N²) overall. That is the obvious objection to
 * building a state machine out of immutable values, and the honest answer is a measurement and a
 * limit rather than an argument.
 *
 * <p>This was found by a benchmark, not by reasoning: a recovery measurement came back at three
 * thousand records a second, the log turned out to be fine, and the cost was here. The fix is a
 * persistent sorted map with structural sharing, which would make each publish O(log N) and which
 * {@code docs/design/0005-scope.md} records as deliberately not implemented — a registry's shape is
 * hundreds of versions per model, and a hand-written HAMT is a different project.
 */
@EnabledIfSystemProperty(named = "cairn.bench", matches = "true")
class KernelScalingTest {

    @Test
    void publishThroughputAgainstVersionsPerModel() {
        System.out.printf("%12s %16s %14s%n", "VERSIONS", "PUBLISHES/S", "TOTAL");
        for (int versions : new int[] {100, 500, 1_000, 5_000, 10_000}) {
            double rate = measure(versions);
            System.out.printf("%12d %,16.0f %14s%n", versions, rate,
                    String.format("%.3fs", versions / rate));
            assertTrue(rate > 10, versions + " versions dropped below ten publishes a second");
        }
    }

    /** Publishes {@code versions} versions of one model and returns the rate. */
    private static double measure(int versions) {
        Digest artifact = Fixtures.digest(1);
        List<Command> script = new ArrayList<>(versions);
        for (int i = 0; i < versions; i++) {
            script.add(Command.PublishVersion.of(
                    Ref.of("fraud", "1." + (i / 1000) + "." + (i % 1000)), artifact, "alice", i));
        }

        PureKernel kernel = new PureKernel();
        kernel.apply(1, new Command.IngestBlob(artifact, 4096));
        long start = System.nanoTime();
        for (int i = 0; i < script.size(); i++) {
            kernel.apply(i + 2, script.get(i));
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        return versions / seconds;
    }

    @Test
    void manyModelsWithFewVersionsEachIsTheShapeThatScales() {
        // The same total number of versions, spread out. If the curve above is really about the
        // per-model map, this is fast at a size where the other one is slow.
        Digest artifact = Fixtures.digest(1);
        int models = 200;
        int perModel = 50;

        PureKernel kernel = new PureKernel();
        kernel.apply(1, new Command.IngestBlob(artifact, 4096));
        long index = 2;
        long start = System.nanoTime();
        for (int m = 0; m < models; m++) {
            for (int v = 0; v < perModel; v++) {
                kernel.apply(index++, Command.PublishVersion.of(
                        Ref.of("m" + m, "1.0." + v), artifact, "alice", v));
            }
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("%12s %,16.0f %14s   (%d models x %d versions)%n",
                "spread", (models * perModel) / seconds,
                String.format("%.3fs", seconds), models, perModel);
        assertTrue((models * perModel) / seconds > 1000);
    }
}
