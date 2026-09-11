package io.cairn.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The rules. One pure function from a state and a command to a new state, an outcome, and the
 * effects the outside world owes as a result.
 *
 * <p>No threads, no clock, no I/O, no randomness, no logging, no dependencies. Everything that
 * varies between two runs of the same command sequence arrives as an argument — the timestamp, the
 * actor, the log index — so a replay of a log produces the state that log produced the first time,
 * on any machine, on any JDK, in any order of execution. That is not an aesthetic claim: it is the
 * precondition for a simulator to be able to say "seed 8123 fails" and mean it tomorrow.
 *
 * <p><b>Why this is a separate module with no dependencies.</b> The state machine this project is
 * derived from was a class that held a RocksDB handle and a Kafka producer, took a
 * {@code byte[]}, split it on colons, wrote to RocksDB, published an event, and caught
 * {@code Exception} around the whole thing to log it. Each of those is a defect on its own; together
 * they make the thing untestable, because there is no way to ask it what it would do. Here the
 * answer to "what would it do" is the return value, and the reason it cannot drift back is that
 * {@code cairn-core} has nothing on its compile path to drift towards.
 *
 * <h2>What the kernel guarantees</h2>
 *
 * <ol>
 *   <li><b>Determinism.</b> Same state, same command, same result, byte for byte.
 *   <li><b>Idempotence in the log index.</b> Applying an index at or below
 *       {@link Registry#appliedIndex()} changes nothing and reports
 *       {@link Outcome.Applied.Kind#DUPLICATE}. A driver that delivers an entry twice — after a
 *       crash, after a restore that overlaps the log — cannot make a replica diverge.
 *   <li><b>Rejection changes nothing but the log position.</b> There is no partial application.
 *   <li><b>Effects are values.</b> The kernel performs no side effect and never could; it appends
 *       what is owed to replicated state, with a sequence number, and returns.
 * </ol>
 *
 * <p>A gap in the log — an index above {@code appliedIndex + 1} — is an
 * {@link IllegalStateException} rather than a rejection. A rejection is an answer to a caller; a
 * gap means the driver has lost an entry, and there is no state in which continuing is better than
 * stopping.
 */
public final class Kernel {

    private Kernel() {}

    /**
     * Applies one command.
     *
     * @param state the registry before the command
     * @param index the log index of this command, 1-based and contiguous
     * @param command what to do
     * @return the state afterwards and the outcome
     * @throws IllegalStateException if {@code index} skips an entry
     */
    public static Transition apply(Registry state, long index, Command command) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        if (index <= state.appliedIndex()) {
            return new Transition(state, Outcome.Applied.duplicate());
        }
        if (index > state.appliedIndex() + 1) {
            throw new IllegalStateException(
                    "log gap: applied " + state.appliedIndex() + ", asked to apply " + index);
        }

        Registry advanced = state.withAppliedIndex(index);
        return switch (command) {
            case Command.IngestBlob c -> ingest(advanced, c);
            case Command.PublishVersion c -> publish(advanced, c);
            case Command.Promote c -> promote(advanced, c);
            case Command.DeleteVersion c -> delete(advanced, c);
            case Command.AckEffects c -> ack(advanced, c);
        };
    }

    // ---- IngestBlob ---------------------------------------------------------------------------

    private static Transition ingest(Registry state, Command.IngestBlob command) {
        Blob existing = state.blobs().get(command.digest());
        if (existing == null) {
            return accepted(state.withBlob(Blob.ingested(command.digest(), command.size())), List.of());
        }
        if (existing.size() != command.size()) {
            // Two different lengths for one SHA-256 is not a race, it is a broken uploader or a
            // broken hash implementation. Recording the second silently would make the registry
            // the place the corruption becomes permanent.
            return rejected(state, RejectionCode.DIGEST_SIZE_MISMATCH,
                    "artifact " + command.digest().shortHex() + " was ingested with size "
                            + existing.size() + ", now offered as " + command.size());
        }
        if (existing.present()) {
            return new Transition(state, Outcome.Applied.idempotent());
        }
        if (!existing.reingestable(state.dispatchedThrough())) {
            // A collection order for these exact bytes is still in the outbox. Accepting this would
            // store them again and then let the collector delete them, and a version published in
            // between would be left pointing at nothing. The simulator found this; RejectionCode
            // has the sequence.
            return rejected(state, RejectionCode.COLLECTION_PENDING,
                    "artifact " + command.digest().shortHex()
                            + " has an undelivered collection order at #" + existing.collectSeq()
                            + "; the effect watermark is at " + state.dispatchedThrough());
        }
        // Re-ingesting something that was collected. Legal and useful: it is how an artifact comes
        // back after the last version referencing it was deleted, and it is why `present` is a flag
        // rather than a removal from the map — the size is already known and must not change.
        return accepted(state.withBlob(existing.reingested()), List.of());
    }

    // ---- PublishVersion ----------------------------------------------------------------------

    private static Transition publish(Registry state, Command.PublishVersion command) {
        Ref ref = command.ref();

        Blob artifact = state.blobs().get(command.artifact());
        if (artifact == null) {
            return rejected(state, RejectionCode.ARTIFACT_MISSING,
                    "no artifact ingested with digest " + command.artifact());
        }
        if (!artifact.present()) {
            // The artifact was collected. Accepting this would produce a version pointing at bytes
            // that a collector has been told to delete, which is the dangling reference the whole
            // reference-counting scheme exists to prevent. Re-ingest the bytes and publish again.
            return rejected(state, RejectionCode.ARTIFACT_MISSING,
                    "artifact " + command.artifact().shortHex()
                            + " has been collected; re-ingest it before publishing");
        }

        Model model = state.models().get(ref.model());
        ModelVersion existing = model == null ? null : model.versions().get(ref.version());

        // Build the candidate first, which normalizes parents and labels, so the comparison below
        // is against the form the version would actually be stored in. Comparing the raw command
        // would make a retry that listed its parents in a different order look like a conflict.
        ModelVersion candidate = new ModelVersion(
                ref.version(),
                command.artifact(),
                Stage.STAGING,
                command.parents(),
                command.labels(),
                command.publishedAt(),
                command.publishedBy(),
                false);

        if (existing != null) {
            if (existing.deleted()) {
                // A tombstone is the record that this version string is spent. Resurrecting it
                // would mean "version 2.1.0" had meant two different sets of bytes over the life
                // of the registry, which is the exact claim immutability is supposed to rule out.
                return rejected(state, RejectionCode.VERSION_DELETED,
                        ref + " was deleted; version identifiers are not reusable");
            }
            boolean identical = existing.artifact().equals(candidate.artifact())
                    && existing.parents().equals(candidate.parents())
                    && existing.labels().equals(candidate.labels());
            if (identical) {
                // A retry. The publisher and timestamp of the *first* publish are kept: they record
                // when this version came into existence, not when somebody last retried telling us.
                return new Transition(state, Outcome.Applied.idempotent());
            }
            return rejected(state, RejectionCode.IMMUTABLE_VERSION,
                    ref + " already exists with artifact " + existing.artifact().shortHex()
                            + "; a published version cannot be changed");
        }

        for (Ref parent : candidate.parents()) {
            if (parent.equals(ref)) {
                return rejected(state, RejectionCode.SELF_PARENT,
                        ref + " cannot be its own parent");
            }
            Model parentModel = state.models().get(parent.model());
            ModelVersion parentVersion =
                    parentModel == null ? null : parentModel.versions().get(parent.version());
            if (parentVersion == null) {
                return rejected(state, RejectionCode.UNKNOWN_PARENT,
                        "declared parent " + parent + " does not exist");
            }
            if (parentVersion.deleted()) {
                return rejected(state, RejectionCode.PARENT_DELETED,
                        "declared parent " + parent + " has been deleted");
            }
        }

        Model base = model == null ? Model.empty(ref.model()) : model;
        Registry next = state
                .withModel(base.with(candidate))
                .withBlob(artifact.withRefCount(artifact.refCount() + 1));

        return accepted(next, List.of(new Effect.VersionPublished(
                ref, candidate.artifact(), candidate.publishedAt(), candidate.publishedBy())));
    }

    // ---- Promote -----------------------------------------------------------------------------

    private static Transition promote(Registry state, Command.Promote command) {
        Ref ref = command.ref();
        Model model = state.models().get(ref.model());
        if (model == null) {
            return rejected(state, RejectionCode.UNKNOWN_MODEL, "no model named " + ref.model());
        }
        ModelVersion version = model.versions().get(ref.version());
        if (version == null) {
            return rejected(state, RejectionCode.UNKNOWN_VERSION, "no version " + ref);
        }
        if (version.deleted()) {
            return rejected(state, RejectionCode.VERSION_DELETED, ref + " has been deleted");
        }

        Stage from = version.stage();
        Stage to = command.target();
        if (from == to) {
            return new Transition(state, Outcome.Applied.idempotent());
        }
        if (!from.canTransitionTo(to)) {
            return rejected(state, RejectionCode.ILLEGAL_TRANSITION,
                    ref + " cannot go from " + from + " to " + to + "; legal targets are "
                            + from.allowedTargets());
        }

        List<Effect> effects = new ArrayList<>(3);
        Model updated = model;

        // The incumbent is demoted in this same transition, which is the whole reason "at most one
        // production version" is a property of the state rather than of a window of time. Ordered
        // before the promotion so a consumer replaying the effect stream never holds two.
        if (to == Stage.PRODUCTION) {
            ModelVersion incumbent = model.production().orElse(null);
            if (incumbent != null && !incumbent.version().equals(ref.version())) {
                updated = updated.with(incumbent.withStage(Stage.ARCHIVED));
                effects.add(new Effect.StageChanged(
                        new Ref(ref.model(), incumbent.version()),
                        Stage.PRODUCTION,
                        Stage.ARCHIVED,
                        command.actor(),
                        command.atMillis()));
            }
        }

        updated = updated.with(version.withStage(to));
        effects.add(new Effect.StageChanged(ref, from, to, command.actor(), command.atMillis()));

        // Emitted only when the answer to "what should serving load?" actually changed, so a
        // consumer can treat every one of these as a reason to act.
        if (to == Stage.PRODUCTION) {
            effects.add(new Effect.ProductionChanged(ref.model(), ref.version()));
        } else if (from == Stage.PRODUCTION) {
            effects.add(new Effect.ProductionChanged(ref.model(), null));
        }

        return accepted(state.withModel(updated), effects);
    }

    // ---- DeleteVersion -----------------------------------------------------------------------

    private static Transition delete(Registry state, Command.DeleteVersion command) {
        Ref ref = command.ref();
        Model model = state.models().get(ref.model());
        if (model == null) {
            return rejected(state, RejectionCode.UNKNOWN_MODEL, "no model named " + ref.model());
        }
        ModelVersion version = model.versions().get(ref.version());
        if (version == null) {
            return rejected(state, RejectionCode.UNKNOWN_VERSION, "no version " + ref);
        }
        if (version.deleted()) {
            return new Transition(state, Outcome.Applied.idempotent());
        }
        if (version.stage() == Stage.PRODUCTION) {
            return rejected(state, RejectionCode.PRODUCTION_VERSION,
                    ref + " is in production; archive or deprecate it first");
        }

        Ref descendant = firstLiveDescendantOf(state, ref);
        if (descendant != null) {
            return rejected(state, RejectionCode.HAS_DESCENDANTS,
                    ref + " is declared as a parent by " + descendant
                            + "; deleting it would leave that lineage dangling");
        }

        List<Effect> effects = new ArrayList<>(2);
        effects.add(new Effect.VersionDeleted(ref, command.actor(), command.atMillis()));

        Registry next = state.withModel(model.with(version.tombstoned()));

        // The reference count is the only thing that decides whether the bytes go. Not the age of
        // the artifact, not a sweep of the blob store against the registry, not a flag on the
        // request: the count reached zero in a committed transition, and the same transition marks
        // the artifact absent so nothing can publish against it in the window before a collector
        // gets to it.
        Blob artifact = state.blobs().get(version.artifact());
        if (artifact != null) {
            int remaining = artifact.refCount() - 1;
            if (remaining > 0) {
                next = next.withBlob(artifact.withRefCount(remaining));
            } else {
                // The collection order's sequence number has to be known here, before the effects
                // are appended, because the artifact records which order covers it. It is the
                // second effect this command produces, so it gets the second number: the first
                // goes to the VersionDeleted already in the list. Asserted rather than assumed,
                // because the arithmetic is only right for as long as the list above it is.
                long orderSeq = state.nextEffectSeq() + effects.size();
                next = next.withBlob(artifact.withRefCount(0).collecting(orderSeq));
                effects.add(new Effect.ArtifactCollected(
                        ref.model(), artifact.digest(), artifact.size()));
            }
        }

        return accepted(next, effects);
    }

    /**
     * Finds a live version anywhere in the registry that declares {@code ancestor} as a parent.
     *
     * <p>A full scan. The alternative is a reverse index in replicated state, which is a second
     * place the parent relation lives and a second thing that can be wrong; the scan is O(versions)
     * on the delete path only, and deletion is not a hot path in a model registry. If it ever
     * becomes one, the right fix is a derived index outside the kernel — see
     * {@code docs/design/0005-scope.md}.
     *
     * <p>Tombstoned descendants are ignored on purpose. Their lineage is history and already
     * points at something that will never be removed, because a tombstone is never removed either.
     */
    private static Ref firstLiveDescendantOf(Registry state, Ref ancestor) {
        for (Model model : state.models().values()) {
            for (ModelVersion candidate : model.versions().values()) {
                if (!candidate.live()) {
                    continue;
                }
                if (candidate.parents().contains(ancestor)) {
                    return new Ref(model.id(), candidate.version());
                }
            }
        }
        return null;
    }

    // ---- AckEffects --------------------------------------------------------------------------

    private static Transition ack(Registry state, Command.AckEffects command) {
        long through = command.throughSeq();
        if (through <= state.dispatchedThrough()) {
            // A redelivered acknowledgement, or one from a dispatcher that restarted and is
            // catching up. Accepted and ignored: the watermark only ever moves forward.
            return new Transition(state, Outcome.Applied.idempotent());
        }
        if (through >= state.nextEffectSeq()) {
            return rejected(state, RejectionCode.ACK_AHEAD_OF_LOG,
                    "acknowledged through " + through + " but the highest effect produced is "
                            + (state.nextEffectSeq() - 1));
        }
        // No effects. An acknowledgement that produced one would need acknowledging in turn.
        return accepted(state.withWatermark(through), List.of());
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static Transition accepted(Registry state, List<Effect> effects) {
        Registry.Appended appended = state.withEffects(effects);
        return new Transition(
                appended.state(),
                new Outcome.Applied(Outcome.Applied.Kind.NEW, appended.effects()));
    }

    private static Transition rejected(Registry state, RejectionCode code, String detail) {
        // `state` here already has the advanced applied index and nothing else: the caller has not
        // touched it. That is what makes "a rejection changes nothing but the log position"
        // structural rather than a thing to remember on every branch above.
        return new Transition(state, new Outcome.Rejected(code, detail));
    }
}
