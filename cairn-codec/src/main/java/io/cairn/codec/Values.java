package io.cairn.codec;

import io.cairn.core.Digest;
import io.cairn.core.Limits;
import io.cairn.core.ModelId;
import io.cairn.core.Ref;
import io.cairn.core.Stage;
import io.cairn.core.VersionId;
import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Encoding for the value types the commands, effects and state all share.
 *
 * <p>Two decisions in here are worth reading.
 *
 * <p><b>Enums are encoded by an explicit tag, not by {@code ordinal()}.</b> An ordinal is a
 * property of the source order of an enum's constants, so using it on disk makes reordering them —
 * an edit no reviewer would question — a silent format change that reinterprets every stored
 * record. The switch below costs four lines and removes that entirely.
 *
 * <p><b>Maps are required to arrive sorted.</b> Writing them sorted makes the encoding
 * deterministic; <i>refusing</i> them unsorted makes it canonical, which is the stronger property
 * the rest of the system relies on: exactly one byte string per state means a SHA-256 over the
 * bytes is a usable identity for a replica, and no peer — buggy or hostile — can produce a second
 * valid encoding of a state that compares as different.
 */
final class Values {

    private Values() {}

    private static final int STAGE_STAGING = 1;
    private static final int STAGE_PRODUCTION = 2;
    private static final int STAGE_ARCHIVED = 3;
    private static final int STAGE_DEPRECATED = 4;

    private static final int DIGEST_SHA256 = 1;

    static void modelId(ByteWriter out, ModelId id) {
        out.string(id.value());
    }

    static ModelId modelId(ByteReader in) {
        return wrap(() -> ModelId.of(in.string(Limits.MAX_MODEL_ID)), "model id");
    }

    static void versionId(ByteWriter out, VersionId version) {
        out.string(version.value());
    }

    static VersionId versionId(ByteReader in) {
        return wrap(() -> VersionId.of(in.string(Limits.MAX_VERSION_ID)), "version");
    }

    static void ref(ByteWriter out, Ref ref) {
        modelId(out, ref.model());
        versionId(out, ref.version());
    }

    static Ref ref(ByteReader in) {
        ModelId model = modelId(in);
        VersionId version = versionId(in);
        return new Ref(model, version);
    }

    /**
     * A digest as an algorithm tag plus its raw bytes.
     *
     * <p>32 bytes rather than 64 hex characters, which halves the size of the field that appears
     * most often in a snapshot, and removes the question of whether a stored digest might be
     * uppercase: there is no case in binary.
     */
    static void digest(ByteWriter out, Digest digest) {
        out.u8(DIGEST_SHA256);
        out.bytes(hexToBytes(digest.hex()));
    }

    static Digest digest(ByteReader in) {
        int algorithm = in.u8();
        if (algorithm != DIGEST_SHA256) {
            throw new CodecException("unknown digest algorithm tag " + algorithm);
        }
        return wrap(() -> Digest.ofSha256(in.bytes(32)), "digest");
    }

    static void stage(ByteWriter out, Stage stage) {
        out.u8(switch (stage) {
            case STAGING -> STAGE_STAGING;
            case PRODUCTION -> STAGE_PRODUCTION;
            case ARCHIVED -> STAGE_ARCHIVED;
            case DEPRECATED -> STAGE_DEPRECATED;
        });
    }

    static Stage stage(ByteReader in) {
        int tag = in.u8();
        return switch (tag) {
            case STAGE_STAGING -> Stage.STAGING;
            case STAGE_PRODUCTION -> Stage.PRODUCTION;
            case STAGE_ARCHIVED -> Stage.ARCHIVED;
            case STAGE_DEPRECATED -> Stage.DEPRECATED;
            default -> throw new CodecException("unknown stage tag " + tag);
        };
    }

    static void actor(ByteWriter out, String actor) {
        out.string(actor);
    }

    static String actor(ByteReader in) {
        return in.string(Limits.MAX_ACTOR);
    }

    /** A sorted parent list, required to be sorted and duplicate-free on the way back in. */
    static void parents(ByteWriter out, java.util.List<Ref> parents) {
        out.uvarint(parents.size());
        Ref previous = null;
        for (Ref parent : parents) {
            if (previous != null && previous.compareTo(parent) >= 0) {
                throw new IllegalArgumentException(
                        "parents must be sorted and distinct, found " + previous + " before "
                                + parent);
            }
            ref(out, parent);
            previous = parent;
        }
    }

    static java.util.List<Ref> parents(ByteReader in) {
        long count = in.uvarint();
        if (count > Limits.MAX_PARENTS) {
            throw new CodecException(
                    count + " parents exceeds the limit of " + Limits.MAX_PARENTS);
        }
        java.util.List<Ref> parents = new java.util.ArrayList<>((int) count);
        Ref previous = null;
        for (long i = 0; i < count; i++) {
            Ref parent = ref(in);
            if (previous != null && previous.compareTo(parent) >= 0) {
                throw new CodecException(
                        "parents must be sorted and distinct, found " + previous + " before "
                                + parent);
            }
            parents.add(parent);
            previous = parent;
        }
        return parents;
    }

    static void labels(ByteWriter out, SortedMap<String, String> labels) {
        out.uvarint(labels.size());
        String previous = null;
        for (var entry : labels.entrySet()) {
            if (previous != null && previous.compareTo(entry.getKey()) >= 0) {
                throw new IllegalArgumentException(
                        "labels must be sorted and distinct, found '" + previous + "' before '"
                                + entry.getKey() + "'");
            }
            out.string(entry.getKey());
            out.string(entry.getValue());
            previous = entry.getKey();
        }
    }

    static SortedMap<String, String> labels(ByteReader in) {
        long count = in.uvarint();
        if (count > Limits.MAX_LABELS) {
            throw new CodecException(count + " labels exceeds the limit of " + Limits.MAX_LABELS);
        }
        TreeMap<String, String> labels = new TreeMap<>();
        String previous = null;
        for (long i = 0; i < count; i++) {
            String key = in.string(Limits.MAX_LABEL_KEY);
            String value = in.string(Limits.MAX_LABEL_VALUE);
            if (previous != null && previous.compareTo(key) >= 0) {
                throw new CodecException(
                        "labels must be sorted and distinct, found '" + previous + "' before '"
                                + key + "'");
            }
            labels.put(key, value);
            previous = key;
        }
        return labels.isEmpty() ? Collections.emptySortedMap() : labels;
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    /**
     * Turns a value type's own validation failure into a decode failure.
     *
     * <p>The core's constructors throw {@link IllegalArgumentException} for an invalid identifier,
     * which is the right type when a caller passes one in. Arriving from a file it means the file
     * is corrupt, and the caller — a log replaying at startup — needs to be able to catch that
     * distinctly from a bug in itself.
     */
    private static <T> T wrap(java.util.function.Supplier<T> decode, String what) {
        try {
            return decode.get();
        } catch (IllegalArgumentException e) {
            throw new CodecException("invalid " + what + " in encoded form: " + e.getMessage(), e);
        }
    }
}
