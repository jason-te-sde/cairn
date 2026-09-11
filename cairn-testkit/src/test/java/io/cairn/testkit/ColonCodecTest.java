package io.cairn.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cairn.codec.Codec;
import io.cairn.core.Blob;
import io.cairn.core.Digest;
import io.cairn.core.Model;
import io.cairn.core.ModelId;
import io.cairn.core.ModelVersion;
import io.cairn.core.Ref;
import io.cairn.core.Registry;
import io.cairn.core.Stage;
import io.cairn.core.VersionId;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The record format this project replaces, failing, twice.
 *
 * <p>{@link Flaw#COLON_CODEC} is the only flaw without an invariant to catch it, because it corrupts
 * a record before there is any state to have a property about. So it is demonstrated directly, and
 * the same values are then put through the current encoding so the difference is in the assertions
 * rather than in a claim.
 */
class ColonCodecTest {

    /** Object keys that contain a colon. None of these is exotic; the first is from a real bucket. */
    @ParameterizedTest
    @ValueSource(strings = {
        "models/fraud/2.1.0:final/model.pt",
        "exports/2026-09-11T03:14:15Z/weights.safetensors",
        "a:b",
    })
    void aColonInTheObjectKeyShiftsEveryFieldAfterIt(String s3Key) {
        ColonCodec.Original record = ColonCodec.Original.sample(s3Key, "the fraud model");

        String[] fields = ColonCodec.decodeFields(ColonCodec.encodeForApply(record));

        assertEquals(record.s3Key().substring(0, record.s3Key().indexOf(':')), fields[3],
                "the key came back truncated at its first colon");
        assertNotEquals(record.fileHash(), fields[4],
                "and the digest field now holds the tail of the key");
        assertThrows(NumberFormatException.class, () -> Long.parseLong(fields[5]),
                "and the size field holds something that is not a number, which is the exception"
                        + " the original caught and logged");
    }

    @Test
    void thatExceptionIsWhereTheReplicasDiverge() {
        // The compound failure, and the reason two of the inherited flaws are really one story. The
        // parse throws inside apply; the original's apply was wrapped in
        // catch (Exception e) { LOG.error(...) }; so the command was dropped on this replica and
        // applied on every other one, and both looked healthy.
        ColonCodec.Original record =
                ColonCodec.Original.sample("models/a:b/model.pt", "the fraud model");

        assertThrows(NumberFormatException.class,
                () -> ColonCodec.decode(ColonCodec.encodeForApply(record)));
        assertTrue(Flaw.SWALLOW_APPLY_ERRORS.inherited());
        assertEquals("I1 Convergence", Flaw.SWALLOW_APPLY_ERRORS.caughtBy());
    }

    @Test
    void theReadPathCorruptedEveryRecordSilently() {
        // The worse of the two, because nothing throws. Eight fields were joined and seven were
        // split, so the last field came back holding the one after it. Every record, every model,
        // every read.
        ColonCodec.Original record =
                ColonCodec.Original.sample("models/fraud/model.pt", "the fraud model");

        ColonCodec.Original readBack = ColonCodec.decode(ColonCodec.encodeForRead(record));

        assertEquals("the fraud model:1700000000000", readBack.description(),
                "the description absorbed the timestamp that followed it");
        assertNotEquals(record.description(), readBack.description());
        assertEquals(record.modelId(), readBack.modelId(),
                "and the first field is fine, which is how this gets past a smoke test");
        assertEquals(record.fileSize(), readBack.fileSize(),
                "as is everything before the last one");
    }

    @Test
    void aRecordWithTooFewFieldsIsTheLuckyOutcome() {
        // An exception is the good case: somebody finds out. The two tests above are the bad case.
        assertThrows(IllegalArgumentException.class, () -> ColonCodec.decode("only:three:fields"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "models/fraud/2.1.0:final/model.pt",
        "exports/2026-09-11T03:14:15Z/weights.safetensors",
        "s3://bucket/key",
        "a:b:c:d:e:f:g",
        "作者:张三",
    })
    void theCurrentEncodingCarriesTheSameValuesUntouched(String value) {
        // Length-prefixed, so a value is allowed to look like structure. Note also that the
        // registry has no bucket or key field at all — an artifact is named by its digest, so the
        // string that broke the old format has nowhere to be in the new one. The label below is the
        // field where arbitrary text is still legal, and it survives.
        ModelVersion version = new ModelVersion(
                VersionId.of("2.1.0"), CommandGenerator.digest(3), Stage.PRODUCTION, null,
                ModelVersion.labels("source", value), 1_700_000_000_000L, "alice", false);

        TreeMap<VersionId, ModelVersion> versions = new TreeMap<>();
        versions.put(version.version(), version);
        TreeMap<ModelId, Model> models = new TreeMap<>();
        models.put(ModelId.of("fraud"), new Model(ModelId.of("fraud"), versions));
        TreeMap<Digest, Blob> blobs = new TreeMap<>();
        blobs.put(CommandGenerator.digest(3),
                new Blob(CommandGenerator.digest(3), 4_194_304L, 1, true, 0));

        Registry before = new Registry(models, blobs, null, 1, 0, 1);
        Registry after = Codec.decodeSnapshot(Codec.encodeSnapshot(before));

        assertEquals(before, after);
        assertEquals(value,
                after.version(Ref.of("fraud", "2.1.0")).orElseThrow().labels().get("source"));
    }

    @Test
    void anIdentifierCannotContainADelimiterInTheFirstPlace() {
        // The second, independent defence. Even a delimiter-based format could not be confused by a
        // model name here, because such a name cannot be constructed.
        assertThrows(IllegalArgumentException.class, () -> ModelId.of("fraud:detector"));
        assertThrows(IllegalArgumentException.class, () -> VersionId.of("2.1.0:rc1"));
        assertTrue(Flaw.COLON_CODEC.inherited(),
                "this defect is inherited from a real system, not invented for the test");
    }
}
