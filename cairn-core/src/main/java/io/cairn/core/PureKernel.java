package io.cairn.core;

import java.util.List;

/**
 * A {@link RegistryKernel} over {@link Kernel}: one field, replaced on every command.
 *
 * <p>Deliberately trivial. All of the behaviour is in the pure function; this class exists because
 * a driver needs somewhere to keep the current state, and because the interface it implements is
 * what lets the test suite substitute a different implementation of the rules underneath the same
 * simulator.
 *
 * <p>Not thread-safe, and that is the right default for a state machine: the server gives one
 * thread ownership of the command path and publishes each new state through a volatile field for
 * readers, which is a contract stated in one place rather than a lock taken in many.
 */
public final class PureKernel implements RegistryKernel {

    private Registry state;

    /** A kernel over an empty registry. */
    public PureKernel() {
        this(Registry.empty());
    }

    /** A kernel over an existing state, which is how a restore from a snapshot starts. */
    public PureKernel(Registry state) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        this.state = state;
    }

    @Override
    public Outcome apply(long index, Command command) {
        Transition transition = Kernel.apply(state, index, command);
        state = transition.state();
        return transition.outcome();
    }

    /**
     * Applies a run of commands starting at {@code firstIndex}, returning every outcome.
     *
     * <p>This is a log replay. It is a fold over a pure function, which is why reconstructing the
     * state at any point in history is a library call rather than a feature with its own code path:
     * {@code cairnctl replay --at=N} and the recovery that runs at startup are the same three lines.
     */
    public List<Outcome> applyAll(long firstIndex, List<Command> commands) {
        List<Outcome> outcomes = new java.util.ArrayList<>(commands.size());
        long index = firstIndex;
        for (Command command : commands) {
            outcomes.add(apply(index++, command));
        }
        return List.copyOf(outcomes);
    }

    /** The current state as the concrete type, for callers that want to snapshot it. */
    public Registry registry() {
        return state;
    }

    @Override
    public RegistryView view() {
        return state;
    }

    @Override
    public String name() {
        return "pure";
    }
}
