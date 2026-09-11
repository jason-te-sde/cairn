package io.cairn.testkit;

import io.cairn.core.Command;
import io.cairn.core.Outcome;
import io.cairn.core.RegistryKernel;
import io.cairn.core.RegistryView;
import io.cairn.core.RejectionCode;
import java.util.EnumMap;
import java.util.Map;

/**
 * Drives one implementation of the rules through a generated command stream, checking invariants.
 *
 * <p>Separate from {@link DifferentialRunner} because the two answer different questions.
 * Differing catches "these two disagree", which is what finds a bug in either. Running one alone
 * with the checks catches "this one breaks a stated property", which is what proves a check works:
 * point it at {@link ReferenceKernel} with a {@link Flaw} enabled and the named property has to
 * fail, on its own, without a correct implementation standing next to it to make the difference
 * obvious.
 */
public final class KernelRunner {

    private KernelRunner() {}

    /**
     * What a run did.
     *
     * @param seed the seed
     * @param steps commands applied
     * @param accepted commands accepted as new work
     * @param idempotent commands accepted as retries
     * @param rejected refusals by code
     * @param checks invariant checks performed
     */
    public record Report(
            long seed,
            int steps,
            int accepted,
            int idempotent,
            Map<RejectionCode, Integer> rejected,
            long checks) {

        /** Total refusals. */
        public int rejectedTotal() {
            int total = 0;
            for (int count : rejected.values()) {
                total += count;
            }
            return total;
        }

        @Override
        public String toString() {
            return "seed=" + seed + " steps=" + steps + " accepted=" + accepted
                    + " idempotent=" + idempotent + " rejected=" + rejectedTotal() + rejected
                    + " checks=" + checks;
        }
    }

    /**
     * Applies {@code steps} generated commands, checking every state property after each one.
     *
     * @throws InvariantViolation on the first property that fails
     */
    public static Report run(long seed, int steps, RegistryKernel kernel) {
        CommandGenerator generator = new CommandGenerator(seed);
        Invariants invariants = new Invariants(seed);

        int accepted = 0;
        int idempotent = 0;
        Map<RejectionCode, Integer> rejected = new EnumMap<>(RejectionCode.class);

        for (int step = 1; step <= steps; step++) {
            // The same standing-in-for-a-dispatcher acknowledgement as the differential runner
            // uses, so the run reaches the states beyond a full outbox.
            if (step % 7 == 0 && kernel.view().nextEffectSeq() > 1) {
                kernel.apply(step, new Command.AckEffects(kernel.view().nextEffectSeq() - 1));
                invariants.check(step, kernel.name(), kernel.view());
                continue;
            }

            Command command = generator.next(step);
            RegistryView before = kernel.view();
            Outcome outcome = kernel.apply(step, command);
            invariants.check(step, kernel.name(), kernel.view());
            if (!outcome.accepted()) {
                invariants.rejectionPurity(step, kernel.name(), before, kernel.view());
            }

            switch (outcome) {
                case Outcome.Applied applied -> {
                    if (applied.kind() == Outcome.Applied.Kind.NEW) {
                        accepted++;
                    } else {
                        idempotent++;
                    }
                }
                case Outcome.Rejected refused -> rejected.merge(refused.code(), 1, Integer::sum);
            }
        }

        return new Report(seed, steps, accepted, idempotent, Map.copyOf(rejected),
                invariants.checkCount());
    }
}
