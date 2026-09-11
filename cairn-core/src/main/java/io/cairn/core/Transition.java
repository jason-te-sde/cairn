package io.cairn.core;

/**
 * The result of applying one command: the state afterwards, and what to tell the caller.
 *
 * <p>Returning both as one value is what keeps the kernel a function. The alternative — mutate the
 * state, return the outcome — is the shape that lets a validation failure leave a partial change
 * behind, and it is the shape that makes a replay impossible to test without a fixture.
 *
 * @param state the registry after the command; the same instance if the command changed nothing
 * @param outcome what happened
 */
public record Transition(Registry state, Outcome outcome) {

    public Transition {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
    }
}
