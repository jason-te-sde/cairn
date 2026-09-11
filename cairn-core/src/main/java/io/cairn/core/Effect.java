package io.cairn.core;

/**
 * Something that has to happen outside the registry because a command was applied.
 *
 * <p>This type is the whole point of the project. The registry it is derived from published to
 * Kafka from inside its state machine's apply method, which is wrong in two directions at once: a
 * replica replaying its log after a restart re-publishes every event it ever published, and a
 * publish that fails is lost while the state change that caused it is durable. Either way the log
 * and the outside world disagree, and nothing in the system can tell you that they do.
 *
 * <p>So an effect is a *value*, produced by a pure function, appended to replicated state with a
 * sequence number, and delivered by somebody else afterwards. The apply path performs no I/O at
 * all, which is what makes it replayable; the effect survives a crash because it is in the same
 * state as the change that caused it; and it is delivered at least once by a dispatcher that
 * retries and at most once by a consumer that remembers a watermark. {@code cairn-effects} is that
 * machinery and {@code docs/design/0003-effects-are-values.md} is the argument.
 */
public sealed interface Effect {

    /** The model this effect concerns, for routing and for cache keys. */
    ModelId model();

    /**
     * A new version exists.
     *
     * @param ref which version
     * @param artifact its content address
     * @param publishedAt the caller-supplied timestamp recorded on the version
     * @param publishedBy who published it
     */
    record VersionPublished(Ref ref, Digest artifact, long publishedAt, String publishedBy)
            implements Effect {
        public VersionPublished {
            requireRef(ref);
            if (artifact == null) {
                throw new IllegalArgumentException("artifact must not be null");
            }
            Names.actor(publishedBy);
        }

        @Override
        public ModelId model() {
            return ref.model();
        }
    }

    /**
     * A version moved between stages.
     *
     * <p>Carries {@code from} as well as {@code to} because a consumer that only learns the new
     * stage cannot tell a promotion from a re-delivery of one, and the pair makes the event
     * self-describing without a lookup against a registry that has since moved on.
     *
     * @param ref which version
     * @param from the stage it left
     * @param to the stage it entered
     * @param actor who moved it
     * @param atMillis the caller-supplied timestamp
     */
    record StageChanged(Ref ref, Stage from, Stage to, String actor, long atMillis)
            implements Effect {
        public StageChanged {
            requireRef(ref);
            if (from == null || to == null) {
                throw new IllegalArgumentException("from and to must not be null");
            }
            if (from == to) {
                throw new IllegalArgumentException("a stage change must change the stage: " + from);
            }
            Names.actor(actor);
        }

        @Override
        public ModelId model() {
            return ref.model();
        }
    }

    /**
     * A version was tombstoned.
     *
     * @param ref which version
     * @param actor who deleted it
     * @param atMillis the caller-supplied timestamp
     */
    record VersionDeleted(Ref ref, String actor, long atMillis) implements Effect {
        public VersionDeleted {
            requireRef(ref);
            Names.actor(actor);
        }

        @Override
        public ModelId model() {
            return ref.model();
        }
    }

    /**
     * An artifact is no longer referenced and its bytes may be removed.
     *
     * <p>This is an instruction to delete data, which makes it the effect where at-most-once
     * matters most and where "the registry decided" has to be the only trigger. A sweep that
     * listed the blob store and deleted anything it could not find a reference for would race
     * every publish in flight; here the decision is a committed state transition that took the
     * reference count to zero, and the same transition marks the artifact absent so a later
     * publish naming it is rejected rather than left pointing at bytes on their way out.
     *
     * @param model the model whose deletion released the artifact, for routing
     * @param digest the artifact to remove
     * @param size its length, so a collector can report how much it freed
     */
    record ArtifactCollected(ModelId model, Digest digest, long size) implements Effect {
        public ArtifactCollected {
            if (model == null) {
                throw new IllegalArgumentException("model must not be null");
            }
            if (digest == null) {
                throw new IllegalArgumentException("digest must not be null");
            }
            if (size < 0) {
                throw new IllegalArgumentException("size must not be negative: " + size);
            }
        }
    }

    /**
     * The production version of a model changed; anything caching it should stop.
     *
     * <p>Redundant with {@link StageChanged} in the sense that it carries no new information, and
     * kept because it is the one effect a serving tier cares about. A cache invalidator that had to
     * reconstruct "did production change?" from a stream of stage transitions would be
     * reimplementing the kernel's rules in a consumer, badly.
     *
     * @param model the model whose production version changed
     * @param production the new production version, or null if the model now has none
     */
    record ProductionChanged(ModelId model, VersionId production) implements Effect {
        public ProductionChanged {
            if (model == null) {
                throw new IllegalArgumentException("model must not be null");
            }
        }
    }

    private static void requireRef(Ref ref) {
        if (ref == null) {
            throw new IllegalArgumentException("ref must not be null");
        }
    }
}
