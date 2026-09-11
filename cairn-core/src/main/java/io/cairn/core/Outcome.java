package io.cairn.core;

import java.util.List;

/**
 * What happened to a command.
 *
 * <p>An outcome is a value with no reference to the state it came from, which is what lets the
 * differential test compare two implementations of the rules: run the same commands through both
 * and the outcomes must match, whatever either one keeps inside.
 */
public sealed interface Outcome {

    /** Whether the command was accepted. A rejection is not an error; it is an answer. */
    boolean accepted();

    /**
     * The command was accepted.
     *
     * @param kind whether it changed anything
     * @param effects the effects it produced, with their assigned sequence numbers, in order
     */
    record Applied(Kind kind, List<SequencedEffect> effects) implements Outcome {

        /** Whether an accepted command actually changed the registry. */
        public enum Kind {
            /** New work: the state changed and the effects below were appended to the outbox. */
            NEW,

            /**
             * A retry of something already done.
             *
             * <p>Accepted, state unchanged, no effects. This is what makes the API safe to retry
             * over a network that loses answers rather than requests: the second publish of an
             * identical version is not a conflict, it is the same publish arriving twice, and the
             * caller gets the same success it missed the first time.
             */
            IDEMPOTENT,

            /**
             * This log index was already applied.
             *
             * <p>Reported rather than rejected, because a replica that is fed an entry twice — a
             * dispatcher restart, a replay that overlaps a snapshot — must reach the same state as
             * one that was fed it once. Making the kernel itself idempotent in the log index means
             * no driver has to be careful, and the simulator injects duplicate delivery precisely
             * to prove that none of them has to be.
             */
            DUPLICATE
        }

        public Applied {
            if (kind == null) {
                throw new IllegalArgumentException("kind must not be null");
            }
            effects = effects == null ? List.of() : List.copyOf(effects);
            if (kind != Kind.NEW && !effects.isEmpty()) {
                throw new IllegalArgumentException(
                        "only a NEW outcome may carry effects, " + kind + " carried "
                                + effects.size());
            }
        }

        /** An accepted command that changed nothing. */
        public static Applied idempotent() {
            return new Applied(Kind.IDEMPOTENT, List.of());
        }

        /** A log entry that had already been applied. */
        public static Applied duplicate() {
            return new Applied(Kind.DUPLICATE, List.of());
        }

        @Override
        public boolean accepted() {
            return true;
        }

        @Override
        public String toString() {
            return kind + (effects.isEmpty() ? "" : " " + effects);
        }
    }

    /**
     * The command was refused.
     *
     * @param code which rule refused it
     * @param detail the specifics, for a log line or an HTTP body
     */
    record Rejected(RejectionCode code, String detail) implements Outcome {

        public Rejected {
            if (code == null) {
                throw new IllegalArgumentException("code must not be null");
            }
            if (detail == null) {
                throw new IllegalArgumentException("detail must not be null");
            }
        }

        @Override
        public boolean accepted() {
            return false;
        }

        @Override
        public String toString() {
            return code + ": " + detail;
        }
    }
}
