package io.cairn.testkit;

import io.cairn.core.Blob;
import io.cairn.core.Command;
import io.cairn.core.Digest;
import io.cairn.core.Effect;
import io.cairn.core.Model;
import io.cairn.core.ModelId;
import io.cairn.core.ModelVersion;
import io.cairn.core.Outcome;
import io.cairn.core.Ref;
import io.cairn.core.RegistryKernel;
import io.cairn.core.RegistryView;
import io.cairn.core.RejectionCode;
import io.cairn.core.SequencedEffect;
import io.cairn.core.Stage;
import io.cairn.core.VersionId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * A second implementation of the same rules, written to be obvious rather than shared.
 *
 * <p>It exists so the real kernel can be differed against something. Two things make that
 * worthwhile rather than circular:
 *
 * <ul>
 *   <li><b>The representation is different.</b> Versions live in one flat map keyed by
 *       {@code model@version} rather than nested per model, and <b>there are no reference counts
 *       at all</b> — the number a {@link Blob} reports is recomputed by scanning every version each
 *       time the state is observed. So the differential test is checking the real kernel's
 *       incrementally maintained count against a full recount, which is the part of the delete path
 *       most likely to be subtly wrong.
 *   <li><b>The state is mutable and the code is imperative.</b> Where the real kernel builds a new
 *       value and returns it, this one edits in place. That means a validation failure here
 *       <i>could</i> leave a partial change behind, which is exactly why I10 exists and why it is
 *       worth running against this implementation too.
 * </ul>
 *
 * <p>What it deliberately does share is the <i>order</i> of validation, because the differential
 * test compares rejection codes and a command can violate two rules at once. That is a real
 * coupling and it is the honest limit of this technique: the test proves the two agree about what
 * each rule does, not that the rules are in the right order.
 *
 * <p>It also takes a set of {@link Flaw}s. Enabling one breaks it in a specific, named way so the
 * suite can be shown to notice — see {@code FlawTest}.
 */
public final class ReferenceKernel implements RegistryKernel {

    /**
     * What the reference knows about an artifact.
     *
     * <p>No reference count: it is recomputed from the versions every time the state is observed,
     * which is the whole point of this implementation existing.
     */
    private record Artifact(long size, boolean present, long collectSeq) {}

    private final TreeMap<Ref, ModelVersion> versions = new TreeMap<>();
    private final TreeMap<Digest, Artifact> artifacts = new TreeMap<>();
    private final List<SequencedEffect> outbox = new ArrayList<>();
    private final Set<Flaw> flaws;
    private final Consumer<SequencedEffect> immediateSink;

    private long nextSeq = 1;
    private long acked;
    private long appliedIndex;

    /** A correct reference implementation. */
    public ReferenceKernel() {
        this(EnumSet.noneOf(Flaw.class), effect -> { });
    }

    /**
     * A reference implementation with specific defects reintroduced.
     *
     * @param flaws which mistakes to make
     * @param immediateSink where {@link Flaw#EFFECTS_IN_APPLY} sends effects, since the whole point
     *     of that flaw is that they do not go to the outbox
     */
    public ReferenceKernel(Set<Flaw> flaws, Consumer<SequencedEffect> immediateSink) {
        this.flaws = EnumSet.copyOf(flaws.isEmpty() ? EnumSet.noneOf(Flaw.class) : flaws);
        this.immediateSink = immediateSink;
    }

    @Override
    public String name() {
        return flaws.isEmpty() ? "reference" : "reference" + flaws;
    }

    @Override
    public Outcome apply(long index, Command command) {
        if (index <= appliedIndex) {
            return Outcome.Applied.duplicate();
        }
        if (index > appliedIndex + 1) {
            throw new IllegalStateException(
                    "log gap: applied " + appliedIndex + ", asked to apply " + index);
        }
        appliedIndex = index;

        if (flaws.contains(Flaw.SWALLOW_APPLY_ERRORS) && looksLikeItWouldFail(command)) {
            // The inherited defect: an error inside apply, caught and logged, and the command
            // reported as if it had been applied. The state change simply does not happen, and
            // nothing downstream can tell.
            return new Outcome.Applied(Outcome.Applied.Kind.NEW, List.of());
        }

        return switch (command) {
            case Command.IngestBlob c -> ingest(c);
            case Command.PublishVersion c -> publish(c);
            case Command.Promote c -> promote(c);
            case Command.DeleteVersion c -> delete(c);
            case Command.AckEffects c -> ack(c);
        };
    }

    /**
     * The trigger for {@link Flaw#SWALLOW_APPLY_ERRORS}.
     *
     * <p>Any deterministic subset of commands would do; labels are used because they are the field
     * the original's colon-joined format choked on, so the two inherited defects fire on the same
     * inputs.
     */
    private static boolean looksLikeItWouldFail(Command command) {
        return command instanceof Command.PublishVersion publish && !publish.labels().isEmpty();
    }

    // ---- the rules, again ---------------------------------------------------------------------

    private Outcome ingest(Command.IngestBlob command) {
        Artifact existing = artifacts.get(command.digest());
        if (existing == null) {
            artifacts.put(command.digest(), new Artifact(command.size(), true, 0));
            return emit();
        }
        if (existing.size() != command.size()) {
            return reject(RejectionCode.DIGEST_SIZE_MISMATCH,
                    "artifact " + command.digest().shortHex() + " was ingested with size "
                            + existing.size() + ", now offered as " + command.size());
        }
        if (existing.present()) {
            return Outcome.Applied.idempotent();
        }
        if (existing.collectSeq() > 0 && acked < existing.collectSeq()) {
            return reject(RejectionCode.COLLECTION_PENDING,
                    "artifact " + command.digest().shortHex() + " is being collected at #"
                            + existing.collectSeq());
        }
        artifacts.put(command.digest(), new Artifact(existing.size(), true, 0));
        return emit();
    }

    private Outcome publish(Command.PublishVersion command) {
        Artifact artifact = artifacts.get(command.artifact());
        if (artifact == null || !artifact.present()) {
            return reject(RejectionCode.ARTIFACT_MISSING,
                    "artifact " + command.artifact() + " is not available");
        }

        ModelVersion existing = versions.get(command.ref());
        ModelVersion candidate = new ModelVersion(
                command.ref().version(), command.artifact(), Stage.STAGING, command.parents(),
                command.labels(), command.publishedAt(), command.publishedBy(), false);

        if (existing != null) {
            if (existing.deleted()) {
                return reject(RejectionCode.VERSION_DELETED, command.ref() + " was deleted");
            }
            boolean same = existing.artifact().equals(candidate.artifact())
                    && existing.parents().equals(candidate.parents())
                    && existing.labels().equals(candidate.labels());
            if (same) {
                return Outcome.Applied.idempotent();
            }
            if (!flaws.contains(Flaw.OVERWRITE_ON_REPUBLISH)) {
                return reject(RejectionCode.IMMUTABLE_VERSION,
                        command.ref() + " already exists with a different artifact");
            }
            // The flaw: treat a second publish as an update. Every field moves, including the one
            // the whole registry is supposed to guarantee.
            versions.put(command.ref(), candidate);
            return emit(new Effect.VersionPublished(
                    command.ref(), candidate.artifact(), candidate.publishedAt(),
                    candidate.publishedBy()));
        }

        for (Ref parent : command.parents()) {
            if (parent.equals(command.ref())) {
                return reject(RejectionCode.SELF_PARENT, command.ref() + " cannot be its own parent");
            }
            ModelVersion ancestor = versions.get(parent);
            if (ancestor == null) {
                return reject(RejectionCode.UNKNOWN_PARENT, "no such parent " + parent);
            }
            if (ancestor.deleted()) {
                return reject(RejectionCode.PARENT_DELETED, "parent " + parent + " was deleted");
            }
        }

        versions.put(command.ref(), candidate);
        return emit(new Effect.VersionPublished(
                command.ref(), candidate.artifact(), candidate.publishedAt(),
                candidate.publishedBy()));
    }

    private Outcome promote(Command.Promote command) {
        if (!knowsModel(command.ref().model())) {
            return reject(RejectionCode.UNKNOWN_MODEL, "no model " + command.ref().model());
        }
        ModelVersion version = versions.get(command.ref());
        if (version == null) {
            return reject(RejectionCode.UNKNOWN_VERSION, "no version " + command.ref());
        }
        if (version.deleted()) {
            return reject(RejectionCode.VERSION_DELETED, command.ref() + " was deleted");
        }
        Stage from = version.stage();
        if (from == command.target()) {
            return Outcome.Applied.idempotent();
        }
        if (!from.canTransitionTo(command.target())) {
            return reject(RejectionCode.ILLEGAL_TRANSITION,
                    command.ref() + " cannot go from " + from + " to " + command.target());
        }

        List<Effect> effects = new ArrayList<>(3);
        if (command.target() == Stage.PRODUCTION) {
            Ref incumbent = productionOf(command.ref().model());
            if (incumbent != null && !incumbent.equals(command.ref())) {
                versions.put(incumbent, withStage(versions.get(incumbent), Stage.ARCHIVED));
                effects.add(new Effect.StageChanged(incumbent, Stage.PRODUCTION, Stage.ARCHIVED,
                        command.actor(), command.atMillis()));
            }
        }
        versions.put(command.ref(), withStage(version, command.target()));
        effects.add(new Effect.StageChanged(
                command.ref(), from, command.target(), command.actor(), command.atMillis()));
        if (command.target() == Stage.PRODUCTION) {
            effects.add(new Effect.ProductionChanged(
                    command.ref().model(), command.ref().version()));
        } else if (from == Stage.PRODUCTION) {
            effects.add(new Effect.ProductionChanged(command.ref().model(), null));
        }
        return emit(effects.toArray(new Effect[0]));
    }

    private Outcome delete(Command.DeleteVersion command) {
        if (!knowsModel(command.ref().model())) {
            return reject(RejectionCode.UNKNOWN_MODEL, "no model " + command.ref().model());
        }
        ModelVersion version = versions.get(command.ref());
        if (version == null) {
            return reject(RejectionCode.UNKNOWN_VERSION, "no version " + command.ref());
        }
        if (version.deleted()) {
            return Outcome.Applied.idempotent();
        }
        if (version.stage() == Stage.PRODUCTION) {
            return reject(RejectionCode.PRODUCTION_VERSION, command.ref() + " is in production");
        }
        for (var entry : versions.entrySet()) {
            if (entry.getValue().live() && entry.getValue().parents().contains(command.ref())) {
                return reject(RejectionCode.HAS_DESCENDANTS,
                        command.ref() + " is a parent of " + entry.getKey());
            }
        }

        versions.put(command.ref(), tombstone(version));

        List<Effect> effects = new ArrayList<>(2);
        effects.add(new Effect.VersionDeleted(command.ref(), command.actor(), command.atMillis()));

        Artifact artifact = artifacts.get(version.artifact());
        if (artifact != null) {
            // Recounted from scratch, because this implementation keeps no count. The flaw skips
            // the count entirely, which is the natural mistake: deletion and collection look like
            // one operation until two versions share an artifact.
            boolean stillReferenced = !flaws.contains(Flaw.COLLECT_WITHOUT_COUNTING)
                    && referencesTo(version.artifact()) > 0;
            if (!stillReferenced) {
                artifacts.put(version.artifact(),
                        new Artifact(artifact.size(), false, nextSeq + effects.size()));
                effects.add(new Effect.ArtifactCollected(
                        command.ref().model(), version.artifact(), artifact.size()));
            }
        }
        return emit(effects.toArray(new Effect[0]));
    }

    private Outcome ack(Command.AckEffects command) {
        if (command.throughSeq() <= acked) {
            return Outcome.Applied.idempotent();
        }
        if (command.throughSeq() >= nextSeq) {
            return reject(RejectionCode.ACK_AHEAD_OF_LOG,
                    "acknowledged through " + command.throughSeq() + " but only " + (nextSeq - 1)
                            + " effects exist");
        }
        acked = command.throughSeq();
        outbox.removeIf(effect -> effect.seq() <= acked);
        return emit();
    }

    // ---- projection --------------------------------------------------------------------------

    @Override
    public RegistryView view() {
        TreeMap<ModelId, Model> models = new TreeMap<>();
        for (var entry : versions.entrySet()) {
            ModelId id = entry.getKey().model();
            Model existing = models.get(id);
            SortedMap<VersionId, ModelVersion> byVersion = existing == null
                    ? new TreeMap<>()
                    : new TreeMap<>(existing.versions());
            byVersion.put(entry.getKey().version(), entry.getValue());
            models.put(id, new Model(id, byVersion));
        }

        TreeMap<Digest, Blob> blobs = new TreeMap<>();
        for (var entry : artifacts.entrySet()) {
            int references = referencesTo(entry.getKey());
            blobs.put(entry.getKey(), new Blob(
                    entry.getKey(), entry.getValue().size(), references,
                    entry.getValue().present(), entry.getValue().collectSeq()));
        }

        return new ProjectedView(
                Collections.unmodifiableSortedMap(models),
                Collections.unmodifiableSortedMap(blobs),
                List.copyOf(outbox),
                nextSeq,
                acked,
                appliedIndex);
    }

    private record ProjectedView(
            SortedMap<ModelId, Model> models,
            SortedMap<Digest, Blob> blobs,
            List<SequencedEffect> outbox,
            long nextEffectSeq,
            long dispatchedThrough,
            long appliedIndex) implements RegistryView {}

    // ---- helpers -----------------------------------------------------------------------------

    /** Counts live references by scanning. The real kernel maintains this incrementally. */
    private int referencesTo(Digest digest) {
        int count = 0;
        for (ModelVersion version : versions.values()) {
            if (version.live() && version.artifact().equals(digest)) {
                count++;
            }
        }
        return count;
    }

    private boolean knowsModel(ModelId model) {
        for (Ref ref : versions.keySet()) {
            if (ref.model().equals(model)) {
                return true;
            }
        }
        return false;
    }

    private Ref productionOf(ModelId model) {
        for (var entry : versions.entrySet()) {
            if (entry.getKey().model().equals(model)
                    && entry.getValue().live()
                    && entry.getValue().stage() == Stage.PRODUCTION) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static ModelVersion withStage(ModelVersion version, Stage stage) {
        return new ModelVersion(version.version(), version.artifact(), stage, version.parents(),
                version.labels(), version.publishedAt(), version.publishedBy(), version.deleted());
    }

    private static ModelVersion tombstone(ModelVersion version) {
        return new ModelVersion(version.version(), version.artifact(), version.stage(),
                version.parents(), version.labels(), version.publishedAt(), version.publishedBy(),
                true);
    }

    private Outcome reject(RejectionCode code, String detail) {
        return new Outcome.Rejected(code, detail);
    }

    private Outcome emit(Effect... produced) {
        if (produced.length == 0) {
            return new Outcome.Applied(Outcome.Applied.Kind.NEW, List.of());
        }
        List<SequencedEffect> sequenced = new ArrayList<>(produced.length);
        for (Effect effect : produced) {
            sequenced.add(new SequencedEffect(nextSeq++, effect));
        }
        if (flaws.contains(Flaw.EFFECTS_IN_APPLY)) {
            // The inherited defect: deliver from inside apply and keep nothing. A replay therefore
            // delivers everything again, and a delivery that fails is simply gone.
            sequenced.forEach(immediateSink);
        } else {
            outbox.addAll(sequenced);
        }
        return new Outcome.Applied(Outcome.Applied.Kind.NEW, sequenced);
    }
}
