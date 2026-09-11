package io.cairn.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Identifier validation, with the cases that matter to this project first.
 *
 * <p>The registry this one is derived from concatenated its fields with colons and split them back
 * apart on read. The first three tests here are the inputs that silently corrupted a record in that
 * design; they are rejected at construction rather than encoded carefully, and the encoding is
 * length-prefixed as well, because one defence that can be forgotten is not a defence.
 */
class NamesTest {

    @Test
    void aModelNameCannotContainTheDelimiterThatUsedToMatter() {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> ModelId.of("fraud:v2"));
        assertTrue(refused.getMessage().contains("may only contain"), refused.getMessage());
    }

    @Test
    void aVersionCannotContainTheDelimiterEither() {
        assertThrows(IllegalArgumentException.class, () -> VersionId.of("1.0.0:rc1"));
    }

    @Test
    void aLabelValueMayContainAColonBecauseItIsData() {
        // The point of a length-prefixed encoding: a value is allowed to look like structure,
        // because nothing reading it back is looking for structure.
        var labels = ModelVersion.labels("source", "s3://bucket/key:with:colons");
        ModelVersion version = new ModelVersion(
                VersionId.of("1.0.0"), Fixtures.digest(1), Stage.STAGING, null, labels,
                0L, "tester", false);
        assertEquals("s3://bucket/key:with:colons", version.labels().get("source"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Fraud", "FRAUD", "fRaud"})
    void modelNamesAreLowercaseSoTwoSpellingsCannotBecomeTwoModels(String candidate) {
        assertThrows(IllegalArgumentException.class, () -> ModelId.of(candidate));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-fraud", "fraud-", ".fraud", "fraud.", "_fraud", "fraud/model",
            "fraud model", "fraud "})
    void malformedModelNamesAreRefused(String candidate) {
        assertThrows(IllegalArgumentException.class, () -> ModelId.of(candidate));
    }

    @ParameterizedTest
    @ValueSource(strings = {"fraud", "fraud-detector", "fraud.detector", "fraud_v2", "f", "0",
            "a-b.c_d0"})
    void wellFormedModelNamesAreAccepted(String candidate) {
        assertEquals(candidate, ModelId.of(candidate).value());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0.0", "1.0.0-rc1", "1.0.0-RC1", "1.0.0+build.5", "v2", "20260911"})
    void versionsKeepTheirCaseAndTheirBuildMetadata(String candidate) {
        assertEquals(candidate, VersionId.of(candidate).value());
    }

    @Test
    void namesAreBounded() {
        String tooLong = "a".repeat(Limits.MAX_MODEL_ID + 1);
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> ModelId.of(tooLong));
        assertTrue(refused.getMessage().contains("at most"), refused.getMessage());
        assertEquals(Limits.MAX_MODEL_ID,
                ModelId.of("a".repeat(Limits.MAX_MODEL_ID)).value().length());
    }

    @Test
    void aRejectedNameIsEscapedBeforeItReachesTheMessage() {
        // A validation failure is the one path on which a hostile string is guaranteed to reach a
        // log, so the message must not be something the string can rewrite.
        String withNewline = "a" + (char) 10 + "b";
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> ModelId.of(withNewline));
        assertTrue(refused.getMessage().contains("\\u000A"),
                "control characters must be escaped in the message: " + refused.getMessage());
        assertTrue(refused.getMessage().indexOf((char) 10) < 0, "the message must stay on one line");
    }

    @Test
    void aLabelValueStillCannotSmuggleControlCharacters() {
        var labels = ModelVersion.labels("k", "a" + (char) 1 + "b");
        assertThrows(IllegalArgumentException.class,
                () -> new ModelVersion(
                        VersionId.of("1.0.0"), Fixtures.digest(1), Stage.STAGING, null,
                        labels, 0L, "tester", false));
    }

    @Test
    void aReferenceRendersWithAtRatherThanAColon() {
        assertEquals("fraud@1.0.0", Ref.of("fraud", "1.0.0").toString());
        assertEquals(Ref.of("fraud", "1.0.0"), Ref.parse("fraud@1.0.0"));
        assertThrows(IllegalArgumentException.class, () -> Ref.parse("fraud:1.0.0"));
        assertThrows(IllegalArgumentException.class, () -> Ref.parse("fraud@1.0.0@extra"));
    }
}
