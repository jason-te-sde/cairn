package io.cairn.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cairn.core.PureKernel;
import io.cairn.core.RejectionCode;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The real kernel against a second implementation of the same rules.
 *
 * <p>The two disagree about almost everything internally. {@link io.cairn.core.Kernel} builds a new
 * immutable value per command and maintains reference counts incrementally;
 * {@link ReferenceKernel} edits mutable maps in place, stores versions in one flat map keyed by
 * {@code model@version}, and keeps <b>no reference counts at all</b> — it recounts by scanning every
 * version whenever its state is observed.
 *
 * <p>That last difference is the one this test is really for. Every step compares the two by
 * canonical digest, which means the kernel's incrementally maintained counts are checked against a
 * full recount on every state the run reaches. The delete path is where a registry like this goes
 * wrong, and "off by one under a sequence nobody wrote a test for" is exactly the shape of bug a
 * hand-written suite misses.
 */
class DifferentialKernelTest {

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 5, 7, 11, 13, 42, 1337, 8123, 20260911})
    void bothImplementationsAgreeOnEverything(long seed) {
        DifferentialRunner.Report report =
                DifferentialRunner.run(seed, 500, new PureKernel(), new ReferenceKernel());

        // And the run was worth comparing. Two implementations that both refused everything would
        // agree perfectly.
        assertTrue(report.accepted() > 30, report + " accepted almost nothing");
        assertTrue(report.idempotent() > 20, report + " never reached a retry");
        assertTrue(report.rejectedTotal() > 80, report + " never refused anything");
        assertTrue(report.rejectionCodesReached() >= 7,
                report + " only reached " + report.rejectionCodesReached() + " rejection codes");
        assertTrue(report.checks() > 500, report.toString());
    }

    @Test
    void theSweepReachesEveryRejectionCodeInBothImplementations() {
        // The assertion that keeps the comparison complete. A rejection code the differential never
        // reaches is a rule the second implementation has never been asked about.
        Set<RejectionCode> reached = EnumSet.noneOf(RejectionCode.class);
        for (long seed = 1; seed <= 40; seed++) {
            reached.addAll(
                    DifferentialRunner.run(seed, 400, new PureKernel(), new ReferenceKernel())
                            .rejected()
                            .keySet());
        }
        Set<RejectionCode> missing = EnumSet.allOf(RejectionCode.class);
        missing.removeAll(reached);
        assertTrue(missing.isEmpty(),
                "the differential sweep never made either implementation answer for " + missing);
    }

    @Test
    void oneSeedIsOneComparisonForever() {
        assertEquals(
                DifferentialRunner.run(8123, 400, new PureKernel(), new ReferenceKernel()),
                DifferentialRunner.run(8123, 400, new PureKernel(), new ReferenceKernel()));
    }

    @Test
    void theReferenceImplementationIsItselfSoundUnderTheInvariants() {
        // Run alone, with the checks. If the reference broke a property on its own, the agreement
        // above would be two implementations being wrong together.
        for (long seed = 1; seed <= 10; seed++) {
            KernelRunner.Report report = KernelRunner.run(seed, 400, new ReferenceKernel());
            assertTrue(report.accepted() > 20, report.toString());
        }
    }

    @Test
    void theRealKernelIsSoundUnderTheInvariantsToo() {
        for (long seed = 1; seed <= 10; seed++) {
            KernelRunner.Report report = KernelRunner.run(seed, 400, new PureKernel());
            assertTrue(report.accepted() > 20, report.toString());
            assertTrue(report.checks() > 400, report.toString());
        }
    }
}
