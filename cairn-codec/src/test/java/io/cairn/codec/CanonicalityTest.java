package io.cairn.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cairn.core.Blob;
import io.cairn.core.Digest;
import io.cairn.core.Model;
import io.cairn.core.ModelId;
import io.cairn.core.ModelVersion;
import io.cairn.core.Ref;
import io.cairn.core.Registry;
import io.cairn.core.RegistryView;
import io.cairn.core.SequencedEffect;
import io.cairn.core.Stage;
import io.cairn.core.VersionId;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * One state, one byte string.
 *
 * <p>Determinism — the same state encodes the same way twice — would be enough for a log to
 * replay. Canonicity is what the rest of the system rests on, and it needs the encoder to refuse
 * input it could not have produced as well as to produce sorted output. Otherwise a caller holding
 * a {@code SortedMap} with a reverse comparator, which is a perfectly legal {@code SortedMap},
 * encodes a state that decodes to something else, and the digest that is supposed to identify a
 * replica identifies the map implementation instead.
 */
class CanonicalityTest {

    /**
     * A view that holds whatever it is given.
     *
     * <p>Deliberately not {@link Registry}: the real state type normalizes its maps into ascending
     * order on construction, which is the first line of defence. This bypasses it so the encoder
     * can be tested as the second one. A registry is not the only thing that can implement
     * {@link RegistryView} — the testkit's second implementation of the rules does too — so the
     * encoder cannot assume its input has been through the core's constructors.
     */
    private record ReversedView(
            SortedMap<ModelId, Model> models,
            SortedMap<Digest, Blob> blobs,
            List<SequencedEffect> outbox,
            long nextEffectSeq,
            long dispatchedThrough,
            long appliedIndex) implements RegistryView {

        ReversedView {
            models = models == null ? java.util.Collections.emptySortedMap() : models;
            blobs = blobs == null ? java.util.Collections.emptySortedMap() : blobs;
            outbox = outbox == null ? List.of() : outbox;
        }
    }

    @Test
    void anEncodedStateIsByteIdenticalAcrossTwoIdenticalHistories() {
        assertArrayEquals(
                Codec.encodeState(CodecFixtures.populated()),
                Codec.encodeState(CodecFixtures.populated()));
    }

    @Test
    void aDescendingModelMapIsRefusedRatherThanReordered() {
        SortedMap<ModelId, Model> descending = new TreeMap<>(Comparator.reverseOrder());
        descending.put(ModelId.of("a"), Model.empty(ModelId.of("a")));
        descending.put(ModelId.of("b"), Model.empty(ModelId.of("b")));

        RegistryView view = new ReversedView(descending, null, null, 1, 0, 0);
        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class, () -> Codec.encodeState(view));
        assertTrue(refused.getMessage().contains("must be sorted"), refused.getMessage());
    }

    @Test
    void aDescendingBlobMapIsRefusedToo() {
        SortedMap<Digest, Blob> descending = new TreeMap<>(Comparator.reverseOrder());
        descending.put(CodecFixtures.digest(1), Blob.ingested(CodecFixtures.digest(1), 1));
        descending.put(CodecFixtures.digest(2), Blob.ingested(CodecFixtures.digest(2), 2));

        RegistryView view = new ReversedView(null, descending, null, 1, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> Codec.encodeState(view));
    }

    @Test
    void aDefensiveCopyIsDefensiveAboutTheOrderingAndNotOnlyTheMutability() {
        // new TreeMap<>(sortedMap) inherits the SOURCE map's comparator, so the obvious defensive
        // copy hands back a descending map when it is given one. Every canonical encoding here
        // assumes ascending keys, which made this the only kind of caller that could have produced
        // a state whose digest depended on which map implementation built it. Found by this test.
        SortedMap<VersionId, ModelVersion> descending = new TreeMap<>(Comparator.reverseOrder());
        for (String v : new String[] {"1.0.0", "2.0.0"}) {
            descending.put(VersionId.of(v), new ModelVersion(
                    VersionId.of(v), CodecFixtures.digest(1), Stage.STAGING, null, null,
                    0L, "tester", false));
        }

        Model model = new Model(ModelId.of("m"), descending);
        assertEquals(
                List.of(VersionId.of("1.0.0"), VersionId.of("2.0.0")),
                List.copyOf(model.versions().keySet()),
                "Model must re-sort into the keys' own order, whatever order it was handed");

        SortedMap<ModelId, Model> models = new TreeMap<>();
        models.put(model.id(), model);
        assertTrue(Codec.encodeState(new ReversedView(models, null, null, 1, 0, 0)).length > 0,
                "and the normalized form then encodes");
    }

    @Test
    void theDigestIgnoresTheEnvelope() {
        Registry state = CodecFixtures.populated();
        // stateDigest is taken over the unframed form on purpose: two builds should agree on what a
        // state is even if one of them writes a newer frame version around it.
        assertTrue(Codec.encodeSnapshot(state).length > Codec.encodeState(state).length);
        assertArrayEquals(Codec.stateDigest(state), Codec.stateDigest(
                Codec.decodeSnapshot(Codec.encodeSnapshot(state))));
    }

    @Test
    void anUnsortedMapIsRefusedOnTheWayBackInAsWell() {
        // Hand-built payload: two models, descending. Nothing this codec writes looks like this,
        // which is exactly why the decoder has to say so rather than accept it.
        ByteWriter out = new ByteWriter();
        out.uvarint(0);
        out.uvarint(1);
        out.uvarint(0);
        out.uvarint(2);
        out.string("b");
        out.uvarint(0);
        out.string("a");
        out.uvarint(0);
        out.uvarint(0);
        out.uvarint(0);

        CodecException refused = assertThrows(
                CodecException.class, () -> Codec.decodeState(out.toByteArray()));
        assertTrue(refused.getMessage().contains("must be sorted"), refused.getMessage());
    }

    @Test
    void duplicateKeysAreRefusedNotCollapsed() {
        ByteWriter out = new ByteWriter();
        out.uvarint(0);
        out.uvarint(1);
        out.uvarint(0);
        out.uvarint(2);
        out.string("a");
        out.uvarint(0);
        out.string("a");
        out.uvarint(0);
        out.uvarint(0);
        out.uvarint(0);

        assertThrows(CodecException.class, () -> Codec.decodeState(out.toByteArray()));
    }

    @Test
    void parentsMustArriveSortedAndDistinct() {
        ByteWriter out = new ByteWriter();
        Values.ref(out, Ref.of("m", "3.0.0"));
        Values.digest(out, CodecFixtures.digest(1));
        out.uvarint(2);
        Values.ref(out, Ref.of("m", "2.0.0"));
        Values.ref(out, Ref.of("m", "1.0.0"));

        ByteWriter command = new ByteWriter();
        command.u8(2);
        command.bytes(out.toByteArray());

        CodecException refused = assertThrows(
                CodecException.class, () -> Codec.decodeCommand(command.toByteArray()));
        assertTrue(refused.getMessage().contains("sorted"), refused.getMessage());
    }

    @Test
    void labelsMustArriveSortedAndDistinct() {
        ByteWriter out = new ByteWriter();
        out.uvarint(2);
        out.string("zebra");
        out.string("v");
        out.string("apple");
        out.string("v");

        ByteReader in = new ByteReader(out.toByteArray());
        CodecException refused = assertThrows(CodecException.class, () -> Values.labels(in));
        assertTrue(refused.getMessage().contains("sorted"), refused.getMessage());
    }

    @Test
    void theEncoderRefusesAnOutboxThatDoesNotMatchItsWatermark() {
        Registry populated = CodecFixtures.populated();
        RegistryView lying = new ReversedView(
                populated.models(),
                populated.blobs(),
                populated.outbox(),
                populated.nextEffectSeq(),
                // Claims one more effect delivered than the outbox reflects, which is the shape a
                // corrupt watermark takes. The encoder notices because the outbox is contiguous
                // from the watermark by construction.
                populated.dispatchedThrough() + 1,
                populated.appliedIndex());

        IllegalArgumentException refused = assertThrows(
                IllegalArgumentException.class, () -> Codec.encodeState(lying));
        assertTrue(refused.getMessage().contains("contiguous"), refused.getMessage());
    }

    @Test
    void everyEnumHasAnExplicitTagRatherThanAnOrdinal() {
        // The guard against somebody reordering the enum: these are the bytes on disk, asserted
        // literally, so a reorder that changed them fails here instead of silently reinterpreting
        // every stored record.
        for (Stage stage : Stage.values()) {
            ByteWriter out = new ByteWriter();
            Values.stage(out, stage);
            int tag = out.toByteArray()[0];
            int expected = switch (stage) {
                case STAGING -> 1;
                case PRODUCTION -> 2;
                case ARCHIVED -> 3;
                case DEPRECATED -> 4;
            };
            assertEquals(expected, tag, stage + " must encode as " + expected);
        }
    }
}
