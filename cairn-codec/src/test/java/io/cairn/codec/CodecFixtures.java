package io.cairn.codec;

import io.cairn.core.Command;
import io.cairn.core.Digest;
import io.cairn.core.Effect;
import io.cairn.core.Kernel;
import io.cairn.core.ModelId;
import io.cairn.core.ModelVersion;
import io.cairn.core.Ref;
import io.cairn.core.Registry;
import io.cairn.core.SequencedEffect;
import io.cairn.core.Stage;
import io.cairn.core.Transition;
import io.cairn.core.VersionId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** States and values the codec tests share. */
final class CodecFixtures {

    private CodecFixtures() {}

    static Digest digest(int n) {
        try {
            return Digest.ofSha256(MessageDigest.getInstance("SHA-256")
                    .digest(("artifact-" + n).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** Every command shape, for a round-trip test that cannot forget one. */
    static List<Command> everyCommand() {
        return List.of(
                new Command.IngestBlob(digest(1), 0),
                new Command.IngestBlob(digest(2), 8L * 1024 * 1024 * 1024),
                Command.PublishVersion.of(Ref.of("fraud", "1.0.0"), digest(1), "alice", 0L),
                new Command.PublishVersion(
                        Ref.of("fraud", "2.0.0"),
                        digest(2),
                        List.of(Ref.of("fraud", "1.0.0"), Ref.of("embeddings", "3.1.4")),
                        ModelVersion.labels(
                                "owner", "risk",
                                "note", "unicode and a colon: é中文 :: ok",
                                "framework", "pytorch"),
                        "bob",
                        1_700_000_000_000L),
                // A timestamp before the epoch, which is why the field is zigzag rather than
                // unsigned: a caller's clock is not the codec's problem to police.
                new Command.PublishVersion(
                        Ref.of("legacy", "0.1"), digest(3), List.of(), null, "carol", -86_400_000L),
                new Command.Promote(Ref.of("fraud", "1.0.0"), Stage.PRODUCTION, "alice", 1L),
                new Command.Promote(Ref.of("fraud", "1.0.0"), Stage.DEPRECATED, "alice", 2L),
                new Command.DeleteVersion(Ref.of("fraud", "1.0.0"), "alice", 3L),
                new Command.AckEffects(0),
                new Command.AckEffects(Long.MAX_VALUE / 2));
    }

    /** Every effect shape, likewise. */
    static List<SequencedEffect> everyEffect() {
        return List.of(
                new SequencedEffect(1, new Effect.VersionPublished(
                        Ref.of("fraud", "1.0.0"), digest(1), 1_700_000_000_000L, "alice")),
                new SequencedEffect(2, new Effect.StageChanged(
                        Ref.of("fraud", "1.0.0"), Stage.STAGING, Stage.PRODUCTION, "alice", 1L)),
                new SequencedEffect(3, new Effect.VersionDeleted(
                        Ref.of("fraud", "1.0.0"), "alice", 2L)),
                new SequencedEffect(4, new Effect.ArtifactCollected(
                        ModelId.of("fraud"), digest(1), 4096)),
                new SequencedEffect(5, new Effect.ProductionChanged(
                        ModelId.of("fraud"), VersionId.of("2.0.0"))),
                new SequencedEffect(6, new Effect.ProductionChanged(ModelId.of("fraud"), null)),
                new SequencedEffect(Integer.MAX_VALUE + 1L, new Effect.VersionDeleted(
                        Ref.of("fraud", "9.9.9"), "alice", Long.MIN_VALUE / 4)));
    }

    /**
     * A registry with something of everything: several models, a tombstone, lineage, labels, a
     * shared artifact, a collected artifact, a partially acknowledged outbox.
     */
    static Registry populated() {
        List<Command> script = List.of(
                new Command.IngestBlob(digest(1), 1024),
                new Command.IngestBlob(digest(2), 2048),
                new Command.IngestBlob(digest(3), 4096),
                new Command.PublishVersion(
                        Ref.of("embeddings", "1.0.0"), digest(1), List.of(),
                        ModelVersion.labels("owner", "platform"), "alice", 1_000L),
                new Command.PublishVersion(
                        Ref.of("fraud", "1.0.0"), digest(2),
                        List.of(Ref.of("embeddings", "1.0.0")),
                        ModelVersion.labels("owner", "risk", "auc", "0.91"), "bob", 2_000L),
                // Shares digest(2) with fraud@1.0.0, so the reference count is two.
                Command.PublishVersion.of(Ref.of("fraud", "1.0.1"), digest(2), "bob", 3_000L),
                Command.PublishVersion.of(Ref.of("churn", "0.9.0"), digest(3), "carol", 4_000L),
                new Command.Promote(Ref.of("fraud", "1.0.0"), Stage.PRODUCTION, "bob", 5_000L),
                new Command.Promote(Ref.of("churn", "0.9.0"), Stage.ARCHIVED, "carol", 6_000L),
                // Tombstones churn@0.9.0 and collects digest(3), which nothing else holds.
                new Command.DeleteVersion(Ref.of("churn", "0.9.0"), "carol", 7_000L),
                new Command.AckEffects(3));

        Registry state = Registry.empty();
        long index = 1;
        for (Command command : script) {
            Transition transition = Kernel.apply(state, index++, command);
            if (!transition.outcome().accepted()) {
                throw new AssertionError("fixture rejected: " + command + " -> "
                        + transition.outcome());
            }
            state = transition.state();
        }
        return state;
    }
}
