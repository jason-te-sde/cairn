package io.cairn.testkit;

import io.cairn.codec.Codec;
import io.cairn.core.Command;
import io.cairn.core.Outcome;
import io.cairn.core.RegistryKernel;
import io.cairn.core.RegistryView;
import io.cairn.core.RejectionCode;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Drives two implementations of the rules through the same commands and requires them to agree.
 *
 * <p>Agreement is checked on three things after every single command, and each one catches a
 * different class of mistake:
 *
 * <ul>
 *   <li><b>The outcome.</b> Accepted, accepted-as-a-retry, or refused with a particular code. This
 *       is what a caller sees, so a disagreement here is a disagreement about the API.
 *   <li><b>The effects.</b> The same effects, with the same sequence numbers, in the same order. A
 *       disagreement here is invisible to a caller and visible to everything downstream.
 *   <li><b>The state, by canonical digest.</b> One 32-byte comparison that covers every field of
 *       every version and artifact. This is the check that catches the kind of bug a test suite
 *       does not have an assertion for, because nobody thought to look at that field.
 * </ul>
 *
 * <p>The second implementation is {@link ReferenceKernel}, which keeps no reference counts at all —
 * it recomputes them by scanning. So the digest comparison is continuously checking the real
 * kernel's incrementally maintained counts against a full recount, on every state it reaches. That
 * is the part of the delete path most likely to be subtly wrong, and it is not something a
 * hand-written test would cover at this density.
 *
 * <p>Both sides are also run through {@link Invariants}, which is how a {@link Flaw} in the
 * reference is caught by name rather than merely showing up as a divergence.
 */
public final class DifferentialRunner {

    private DifferentialRunner() {}

    /**
     * What a comparison run did.
     *
     * @param seed the seed
     * @param steps commands compared
     * @param accepted commands both sides accepted as new work
     * @param idempotent commands both sides accepted as retries
     * @param rejected refusals by code
     * @param checks invariant checks performed across both sides
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

        /** How many distinct rejection codes the run reached. */
        public int rejectionCodesReached() {
            return rejected.size();
        }

        @Override
        public String toString() {
            return "seed=" + seed + " steps=" + steps + " accepted=" + accepted
                    + " idempotent=" + idempotent + " rejected=" + rejectedTotal() + rejected
                    + " checks=" + checks;
        }
    }

    /**
     * Runs {@code steps} generated commands through both kernels.
     *
     * @throws InvariantViolation if the two disagree, or if either one breaks a property
     */
    public static Report run(long seed, int steps, RegistryKernel left, RegistryKernel right) {
        CommandGenerator generator = new CommandGenerator(seed);
        Invariants leftChecks = new Invariants(seed);
        Invariants rightChecks = new Invariants(seed);

        int accepted = 0;
        int idempotent = 0;
        Map<RejectionCode, Integer> rejected = new EnumMap<>(RejectionCode.class);

        for (int step = 1; step <= steps; step++) {
            // Every so often, stand in for a dispatcher that is keeping up. There is no downstream
            // world here, so this is not an exactly-once claim; it is what lets the run reach the
            // states beyond a full outbox — an artifact whose collection order has been delivered
            // and can therefore be re-ingested, for one — and it exercises the acknowledgement
            // path in both implementations. Both sides get the identical command, so agreement is
            // still the only thing being asserted.
            if (step % 23 == 0) {
                // An acknowledgement the registry cannot possibly owe. Both implementations have to
                // refuse it for the same reason, which is the only way ACK_AHEAD_OF_LOG gets
                // compared at all now that the generator no longer produces acknowledgements.
                Command ahead = new Command.AckEffects(left.view().nextEffectSeq() + 2);
                compareOutcomes(seed, step, ahead, left, right,
                        left.apply(step, ahead), right.apply(step, ahead));
                rejected.merge(RejectionCode.ACK_AHEAD_OF_LOG, 1, Integer::sum);
                continue;
            }
            if (step % 7 == 0 && left.view().nextEffectSeq() > 1) {
                long through = left.view().nextEffectSeq() - 1;
                Outcome leftAck = left.apply(step, new Command.AckEffects(through));
                Outcome rightAck = right.apply(step, new Command.AckEffects(through));
                compareOutcomes(seed, step, new Command.AckEffects(through),
                        left, right, leftAck, rightAck);
                continue;
            }

            Command command = generator.next(step);

            RegistryView leftBefore = left.view();
            RegistryView rightBefore = right.view();
            Outcome leftOutcome = left.apply(step, command);
            Outcome rightOutcome = right.apply(step, command);

            compareOutcomes(seed, step, command, left, right, leftOutcome, rightOutcome);

            // The digest comparison. Everything above is about what the two said; this is about
            // what they are, and it is the assertion that does not depend on anybody having
            // thought of the field that went wrong.
            String leftDigest = Codec.stateDigestHex(left.view());
            String rightDigest = Codec.stateDigestHex(right.view());
            if (!leftDigest.equals(rightDigest)) {
                Map<String, RegistryView> sides = new LinkedHashMap<>();
                sides.put(left.name(), left.view());
                sides.put(right.name(), right.view());
                // Reuses the convergence check so the failure is reported under the same name a
                // divergence between replicas would be, which is what Flaw.caughtBy refers to.
                new Invariants(seed).convergence(step, sides);
                throw new InvariantViolation("I1 Convergence", seed, step,
                        "state digests differ after " + command + " but the difference was not"
                                + " describable: " + leftDigest + " vs " + rightDigest);
            }

            leftChecks.check(step, left.name(), left.view());
            rightChecks.check(step, right.name(), right.view());
            if (!leftOutcome.accepted()) {
                leftChecks.rejectionPurity(step, left.name(), leftBefore, left.view());
                rightChecks.rejectionPurity(step, right.name(), rightBefore, right.view());
            }

            switch (leftOutcome) {
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
                leftChecks.checkCount() + rightChecks.checkCount());
    }

    private static void compareOutcomes(
            long seed,
            long step,
            Command command,
            RegistryKernel left,
            RegistryKernel right,
            Outcome leftOutcome,
            Outcome rightOutcome) {

        if (leftOutcome.accepted() != rightOutcome.accepted()) {
            throw new InvariantViolation("I1 Convergence", seed, step,
                    left.name() + " said " + leftOutcome + " and " + right.name() + " said "
                            + rightOutcome + " for " + command);
        }
        if (leftOutcome instanceof Outcome.Rejected leftRefused
                && rightOutcome instanceof Outcome.Rejected rightRefused
                && leftRefused.code() != rightRefused.code()) {
            throw new InvariantViolation("I1 Convergence", seed, step,
                    "the two implementations refused " + command + " for different reasons: "
                            + left.name() + " said " + leftRefused.code() + ", " + right.name()
                            + " said " + rightRefused.code());
        }
        if (leftOutcome instanceof Outcome.Applied leftApplied
                && rightOutcome instanceof Outcome.Applied rightApplied) {
            if (leftApplied.kind() != rightApplied.kind()) {
                throw new InvariantViolation("I1 Convergence", seed, step,
                        "the two implementations classified " + command + " differently: "
                                + left.name() + " said " + leftApplied.kind() + ", "
                                + right.name() + " said " + rightApplied.kind());
            }
            if (!leftApplied.effects().equals(rightApplied.effects())) {
                throw new InvariantViolation("I1 Convergence", seed, step,
                        "the two implementations produced different effects for " + command + ": "
                                + leftApplied.effects() + " vs " + rightApplied.effects());
            }
        }
    }
}
