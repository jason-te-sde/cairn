package io.cairn.codec;

import io.cairn.core.Blob;
import io.cairn.core.Command;
import io.cairn.core.Digest;
import io.cairn.core.Effect;
import io.cairn.core.Model;
import io.cairn.core.ModelId;
import io.cairn.core.ModelVersion;
import io.cairn.core.Ref;
import io.cairn.core.Registry;
import io.cairn.core.RegistryView;
import io.cairn.core.SequencedEffect;
import io.cairn.core.Stage;
import io.cairn.core.VersionId;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * The canonical encoding: one state, one byte string.
 *
 * <p>Determinism would be enough for a log to replay the same way twice. Canonicity is the stronger
 * property and it is what the rest of the system is built on: because every map is written sorted
 * <i>and refused unsorted</i>, and because there is exactly one way to write every value, a
 * SHA-256 over the encoding of a registry is an identity for that registry. Comparing two replicas
 * is then 32 bytes rather than a walk, {@code cairnctl verify} can re-derive the state from the log
 * and compare it to the snapshot in one line, and an invariant that says "these two implementations
 * agree" is a single assertion.
 *
 * <p>The format is length-prefixed throughout, which is the direct answer to the format this
 * project exists to replace — {@code join(":")} on the way out and {@code split(":", 7)} on the way
 * back in, so a model named {@code a:b} silently became a different record and a description
 * containing a colon truncated everything after it. There is no delimiter here for a value to
 * contain. The identifier rules in {@code io.cairn.core.Names} make such a name impossible in the
 * first place, and the two defences are deliberately independent.
 *
 * <p>All decode failures are {@link CodecException}. The decoder is strict on purpose: trailing
 * bytes, an unsorted map, a boolean that is not 0 or 1, a length that exceeds the input, malformed
 * UTF-8, and an outbox whose sequence numbers do not match the watermark are all rejected rather
 * than interpreted. {@code docs/testing.md} lists what the rejection corpus covers.
 */
public final class Codec {

    private Codec() {}

    private static final int CMD_INGEST_BLOB = 1;
    private static final int CMD_PUBLISH_VERSION = 2;
    private static final int CMD_PROMOTE = 3;
    private static final int CMD_DELETE_VERSION = 4;
    private static final int CMD_ACK_EFFECTS = 5;

    private static final int EFF_VERSION_PUBLISHED = 1;
    private static final int EFF_STAGE_CHANGED = 2;
    private static final int EFF_VERSION_DELETED = 3;
    private static final int EFF_ARTIFACT_COLLECTED = 4;
    private static final int EFF_PRODUCTION_CHANGED = 5;

    // ---- commands ----------------------------------------------------------------------------

    /**
     * Encodes a command, without a frame.
     *
     * <p>Frameless because the log supplies its own per-record length and checksum, and a second
     * envelope inside the first would be four bytes of magic per entry proving something the file
     * header already proved.
     */
    public static byte[] encodeCommand(Command command) {
        ByteWriter out = new ByteWriter();
        switch (command) {
            case Command.IngestBlob c -> {
                out.u8(CMD_INGEST_BLOB);
                Values.digest(out, c.digest());
                out.uvarint(c.size());
            }
            case Command.PublishVersion c -> {
                out.u8(CMD_PUBLISH_VERSION);
                Values.ref(out, c.ref());
                Values.digest(out, c.artifact());
                Values.parents(out, c.parents());
                Values.labels(out, c.labels());
                Values.actor(out, c.publishedBy());
                out.svarint(c.publishedAt());
            }
            case Command.Promote c -> {
                out.u8(CMD_PROMOTE);
                Values.ref(out, c.ref());
                Values.stage(out, c.target());
                Values.actor(out, c.actor());
                out.svarint(c.atMillis());
            }
            case Command.DeleteVersion c -> {
                out.u8(CMD_DELETE_VERSION);
                Values.ref(out, c.ref());
                Values.actor(out, c.actor());
                out.svarint(c.atMillis());
            }
            case Command.AckEffects c -> {
                out.u8(CMD_ACK_EFFECTS);
                out.uvarint(c.throughSeq());
            }
        }
        return out.toByteArray();
    }

    /** Decodes a command, requiring the input to be exactly one. */
    public static Command decodeCommand(byte[] encoded) {
        ByteReader in = new ByteReader(encoded);
        Command command = readCommand(in);
        in.end();
        return command;
    }

    private static Command readCommand(ByteReader in) {
        int tag = in.u8();
        try {
            return switch (tag) {
                case CMD_INGEST_BLOB -> {
                    Digest digest = Values.digest(in);
                    yield new Command.IngestBlob(digest, in.uvarint());
                }
                case CMD_PUBLISH_VERSION -> {
                    Ref ref = Values.ref(in);
                    Digest artifact = Values.digest(in);
                    List<Ref> parents = Values.parents(in);
                    var labels = Values.labels(in);
                    String by = Values.actor(in);
                    yield new Command.PublishVersion(
                            ref, artifact, parents, labels, by, in.svarint());
                }
                case CMD_PROMOTE -> {
                    Ref ref = Values.ref(in);
                    Stage target = Values.stage(in);
                    String actor = Values.actor(in);
                    yield new Command.Promote(ref, target, actor, in.svarint());
                }
                case CMD_DELETE_VERSION -> {
                    Ref ref = Values.ref(in);
                    String actor = Values.actor(in);
                    yield new Command.DeleteVersion(ref, actor, in.svarint());
                }
                case CMD_ACK_EFFECTS -> new Command.AckEffects(in.uvarint());
                default -> throw new CodecException("unknown command tag " + tag);
            };
        } catch (IllegalArgumentException e) {
            throw new CodecException("invalid command with tag " + tag + ": " + e.getMessage(), e);
        }
    }

    // ---- effects -----------------------------------------------------------------------------

    /** Encodes one sequenced effect, without a frame. */
    public static byte[] encodeEffect(SequencedEffect effect) {
        ByteWriter out = new ByteWriter();
        writeSequencedEffect(out, effect);
        return out.toByteArray();
    }

    /** Decodes one sequenced effect, requiring the input to be exactly one. */
    public static SequencedEffect decodeEffect(byte[] encoded) {
        ByteReader in = new ByteReader(encoded);
        SequencedEffect effect = readSequencedEffect(in);
        in.end();
        return effect;
    }

    private static void writeSequencedEffect(ByteWriter out, SequencedEffect sequenced) {
        out.uvarint(sequenced.seq());
        switch (sequenced.effect()) {
            case Effect.VersionPublished e -> {
                out.u8(EFF_VERSION_PUBLISHED);
                Values.ref(out, e.ref());
                Values.digest(out, e.artifact());
                out.svarint(e.publishedAt());
                Values.actor(out, e.publishedBy());
            }
            case Effect.StageChanged e -> {
                out.u8(EFF_STAGE_CHANGED);
                Values.ref(out, e.ref());
                Values.stage(out, e.from());
                Values.stage(out, e.to());
                Values.actor(out, e.actor());
                out.svarint(e.atMillis());
            }
            case Effect.VersionDeleted e -> {
                out.u8(EFF_VERSION_DELETED);
                Values.ref(out, e.ref());
                Values.actor(out, e.actor());
                out.svarint(e.atMillis());
            }
            case Effect.ArtifactCollected e -> {
                out.u8(EFF_ARTIFACT_COLLECTED);
                Values.modelId(out, e.model());
                Values.digest(out, e.digest());
                out.uvarint(e.size());
            }
            case Effect.ProductionChanged e -> {
                out.u8(EFF_PRODUCTION_CHANGED);
                Values.modelId(out, e.model());
                // A model with no production version is a real state, so the field is optional
                // rather than encoded as an empty string: an empty version identifier is not a
                // legal one, and a codec that relied on that would be relying on a validation rule
                // in another module staying exactly as it is.
                out.bool(e.production() != null);
                if (e.production() != null) {
                    Values.versionId(out, e.production());
                }
            }
        }
    }

    private static SequencedEffect readSequencedEffect(ByteReader in) {
        long seq = in.uvarint();
        int tag = in.u8();
        try {
            Effect effect = switch (tag) {
                case EFF_VERSION_PUBLISHED -> {
                    Ref ref = Values.ref(in);
                    Digest artifact = Values.digest(in);
                    long at = in.svarint();
                    yield new Effect.VersionPublished(ref, artifact, at, Values.actor(in));
                }
                case EFF_STAGE_CHANGED -> {
                    Ref ref = Values.ref(in);
                    Stage from = Values.stage(in);
                    Stage to = Values.stage(in);
                    String actor = Values.actor(in);
                    yield new Effect.StageChanged(ref, from, to, actor, in.svarint());
                }
                case EFF_VERSION_DELETED -> {
                    Ref ref = Values.ref(in);
                    String actor = Values.actor(in);
                    yield new Effect.VersionDeleted(ref, actor, in.svarint());
                }
                case EFF_ARTIFACT_COLLECTED -> {
                    ModelId model = Values.modelId(in);
                    Digest digest = Values.digest(in);
                    yield new Effect.ArtifactCollected(model, digest, in.uvarint());
                }
                case EFF_PRODUCTION_CHANGED -> {
                    ModelId model = Values.modelId(in);
                    VersionId production = in.bool() ? Values.versionId(in) : null;
                    yield new Effect.ProductionChanged(model, production);
                }
                default -> throw new CodecException("unknown effect tag " + tag);
            };
            return new SequencedEffect(seq, effect);
        } catch (IllegalArgumentException e) {
            throw new CodecException("invalid effect with tag " + tag + ": " + e.getMessage(), e);
        }
    }

    // ---- state -------------------------------------------------------------------------------

    /** Encodes a whole registry as a framed snapshot. */
    public static byte[] encodeSnapshot(RegistryView state) {
        return Frame.wrap(Frame.MAGIC_SNAPSHOT, encodeState(state));
    }

    /**
     * Encodes a registry without the frame.
     *
     * <p>Public because this, not the framed form, is what a state digest is taken over: the frame
     * carries a format version, and two states should compare equal or not on what they hold rather
     * than on which build wrote them down.
     */
    public static byte[] encodeState(RegistryView state) {
        ByteWriter out = new ByteWriter(4096);
        out.uvarint(state.appliedIndex());
        out.uvarint(state.nextEffectSeq());
        out.uvarint(state.dispatchedThrough());

        out.uvarint(state.models().size());
        ModelId previousModel = null;
        for (Model model : state.models().values()) {
            if (previousModel != null && previousModel.compareTo(model.id()) >= 0) {
                throw new IllegalArgumentException(
                        "models must be sorted and distinct, found " + previousModel + " before "
                                + model.id());
            }
            Values.modelId(out, model.id());
            out.uvarint(model.versions().size());
            VersionId previousVersion = null;
            for (ModelVersion version : model.versions().values()) {
                if (previousVersion != null && previousVersion.compareTo(version.version()) >= 0) {
                    throw new IllegalArgumentException(
                            "versions of " + model.id() + " must be sorted and distinct, found "
                                    + previousVersion + " before " + version.version());
                }
                writeVersion(out, version);
                previousVersion = version.version();
            }
            previousModel = model.id();
        }

        out.uvarint(state.blobs().size());
        Digest previousDigest = null;
        for (Blob blob : state.blobs().values()) {
            if (previousDigest != null && previousDigest.compareTo(blob.digest()) >= 0) {
                throw new IllegalArgumentException(
                        "blobs must be sorted and distinct, found " + previousDigest + " before "
                                + blob.digest());
            }
            Values.digest(out, blob.digest());
            out.uvarint(blob.size());
            out.uvarint(blob.refCount());
            out.bool(blob.present());
            out.uvarint(blob.collectSeq());
            previousDigest = blob.digest();
        }

        // The outbox holds exactly the effects in (dispatchedThrough, nextEffectSeq), contiguous
        // and in order, because that is the only way the kernel can produce one. The count is
        // therefore implied, and writing it anyway would be writing down a number the reader can
        // check — which is exactly why it is written: the check below is free and it catches a
        // corrupt watermark that no per-field validation would.
        out.uvarint(state.outbox().size());
        long expectedSeq = state.dispatchedThrough() + 1;
        for (SequencedEffect effect : state.outbox()) {
            if (effect.seq() != expectedSeq) {
                throw new IllegalArgumentException(
                        "outbox must be contiguous from " + (state.dispatchedThrough() + 1)
                                + "; expected #" + expectedSeq + ", found #" + effect.seq());
            }
            writeSequencedEffect(out, effect);
            expectedSeq++;
        }
        if (expectedSeq != state.nextEffectSeq()) {
            throw new IllegalArgumentException(
                    "outbox ends at #" + (expectedSeq - 1) + " but the next sequence number is #"
                            + state.nextEffectSeq());
        }
        return out.toByteArray();
    }

    /** Decodes a framed snapshot. */
    public static Registry decodeSnapshot(byte[] framed) {
        ByteReader in = Frame.unwrap(Frame.MAGIC_SNAPSHOT, framed);
        Registry state = readState(in);
        in.end();
        return state;
    }

    /** Decodes the unframed form, for a caller that has already verified its own envelope. */
    public static Registry decodeState(byte[] encoded) {
        ByteReader in = new ByteReader(encoded);
        Registry state = readState(in);
        in.end();
        return state;
    }

    private static Registry readState(ByteReader in) {
        long appliedIndex = in.uvarint();
        long nextEffectSeq = in.uvarint();
        long dispatchedThrough = in.uvarint();

        TreeMap<ModelId, Model> models = new TreeMap<>();
        long modelCount = bounded(in.uvarint(), in, "models");
        ModelId previousModel = null;
        for (long i = 0; i < modelCount; i++) {
            ModelId id = Values.modelId(in);
            if (previousModel != null && previousModel.compareTo(id) >= 0) {
                throw new CodecException(
                        "models must be sorted and distinct, found " + previousModel + " before "
                                + id);
            }
            TreeMap<VersionId, ModelVersion> versions = new TreeMap<>();
            long versionCount = bounded(in.uvarint(), in, "versions of " + id);
            VersionId previousVersion = null;
            for (long v = 0; v < versionCount; v++) {
                ModelVersion version = readVersion(in);
                if (previousVersion != null && previousVersion.compareTo(version.version()) >= 0) {
                    throw new CodecException(
                            "versions of " + id + " must be sorted and distinct, found "
                                    + previousVersion + " before " + version.version());
                }
                versions.put(version.version(), version);
                previousVersion = version.version();
            }
            models.put(id, construct(() -> new Model(id, versions), "model " + id));
            previousModel = id;
        }

        TreeMap<Digest, Blob> blobs = new TreeMap<>();
        long blobCount = bounded(in.uvarint(), in, "blobs");
        Digest previousDigest = null;
        for (long i = 0; i < blobCount; i++) {
            Digest digest = Values.digest(in);
            if (previousDigest != null && previousDigest.compareTo(digest) >= 0) {
                throw new CodecException(
                        "blobs must be sorted and distinct, found " + previousDigest + " before "
                                + digest);
            }
            long size = in.uvarint();
            long refCount = in.uvarint();
            if (refCount > Integer.MAX_VALUE) {
                throw new CodecException("refCount " + refCount + " does not fit in an int");
            }
            boolean present = in.bool();
            long collectSeq = in.uvarint();
            blobs.put(digest, construct(
                    () -> new Blob(digest, size, (int) refCount, present, collectSeq),
                    "blob " + digest.shortHex()));
            previousDigest = digest;
        }

        long outboxCount = bounded(in.uvarint(), in, "outbox");
        List<SequencedEffect> outbox = new ArrayList<>((int) outboxCount);
        long expectedSeq = dispatchedThrough + 1;
        for (long i = 0; i < outboxCount; i++) {
            SequencedEffect effect = readSequencedEffect(in);
            if (effect.seq() != expectedSeq) {
                throw new CodecException(
                        "outbox must be contiguous from #" + (dispatchedThrough + 1)
                                + "; expected #" + expectedSeq + ", found #" + effect.seq());
            }
            outbox.add(effect);
            expectedSeq++;
        }
        if (expectedSeq != nextEffectSeq) {
            throw new CodecException(
                    "outbox holds " + outboxCount + " effects ending at #" + (expectedSeq - 1)
                            + ", but the watermark is " + dispatchedThrough
                            + " and the next sequence number is " + nextEffectSeq);
        }

        return construct(
                () -> new Registry(
                        models, blobs, outbox, nextEffectSeq, dispatchedThrough, appliedIndex),
                "registry");
    }

    private static void writeVersion(ByteWriter out, ModelVersion version) {
        Values.versionId(out, version.version());
        Values.digest(out, version.artifact());
        Values.stage(out, version.stage());
        Values.parents(out, version.parents());
        Values.labels(out, version.labels());
        out.svarint(version.publishedAt());
        Values.actor(out, version.publishedBy());
        out.bool(version.deleted());
    }

    private static ModelVersion readVersion(ByteReader in) {
        VersionId id = Values.versionId(in);
        Digest artifact = Values.digest(in);
        Stage stage = Values.stage(in);
        List<Ref> parents = Values.parents(in);
        var labels = Values.labels(in);
        long publishedAt = in.svarint();
        String publishedBy = Values.actor(in);
        boolean deleted = in.bool();
        return construct(
                () -> new ModelVersion(
                        id, artifact, stage, parents, labels, publishedAt, publishedBy, deleted),
                "version " + id);
    }

    // ---- digests -----------------------------------------------------------------------------

    /**
     * SHA-256 over the canonical encoding of a state.
     *
     * <p>This is the value the whole test suite leans on. Two replicas agree if and only if this
     * matches, which turns "did the cluster converge?" from a structural comparison into an
     * equality check, and makes the failure message from a divergence short enough to read.
     */
    public static byte[] stateDigest(RegistryView state) {
        return sha256(encodeState(state));
    }

    /** {@link #stateDigest} as lowercase hex. */
    public static String stateDigestHex(RegistryView state) {
        return hex(stateDigest(state));
    }

    /** The first twelve hex characters of {@link #stateDigest}, for a log line. */
    public static String stateFingerprint(RegistryView state) {
        return stateDigestHex(state).substring(0, 12);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // Every conforming JRE has SHA-256. If this ever throws, nothing downstream is
            // salvageable, so it is not something a caller should be made to handle.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    /**
     * Refuses a count that could not possibly be satisfied by the bytes that remain.
     *
     * <p>A corrupt varint can say four billion. Without this, the next line allocates a list that
     * size and the process dies with an {@code OutOfMemoryError} in a thread that has nothing to do
     * with the corrupt file. Every record costs at least one byte, so the remaining input is a
     * sound upper bound and needs no per-type knowledge.
     */
    private static long bounded(long count, ByteReader in, String what) {
        if (count > in.remaining()) {
            throw new CodecException(
                    count + " " + what + " claimed but only " + in.remaining()
                            + " bytes remain at offset " + in.position());
        }
        return count;
    }

    private static <T> T construct(java.util.function.Supplier<T> build, String what) {
        try {
            return build.get();
        } catch (IllegalArgumentException e) {
            throw new CodecException("invalid " + what + ": " + e.getMessage(), e);
        }
    }
}
