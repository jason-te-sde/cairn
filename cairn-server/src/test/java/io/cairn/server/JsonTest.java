package io.cairn.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The JSON codec, judged on what it refuses.
 *
 * <p>The corpus below is the justification for having written this rather than taken a library: a
 * general-purpose parser accepts most of it, because it is built to read what people send. At the
 * edge of a system whose argument is that it does not trust its input, that is the wrong default.
 */
class JsonTest {

    @Test
    void anObjectRoundTripsThroughTheWriterAndTheParser() {
        String rendered = new Json.Writer()
                .field("model", "fraud")
                .field("size", 4096)
                .field("deleted", false)
                .field("absent", (String) null)
                .strings("parents", List.of("a@1.0.0", "b@2.0.0"))
                .object("labels", Map.of("owner", "risk"))
                .done();

        Map<String, Object> parsed = Json.parseObject(rendered);
        assertEquals("fraud", Json.string(parsed, "model"));
        assertEquals(4096L, parsed.get("size"));
        assertEquals(Boolean.FALSE, parsed.get("deleted"));
        assertEquals(null, parsed.get("absent"));
        assertEquals(List.of("a@1.0.0", "b@2.0.0"), Json.strings(parsed, "parents"));
        assertEquals(Map.of("owner", "risk"), Json.stringMap(parsed, "labels"));
    }

    @Test
    void everyCharacterThatCouldBreakAConsumerIsEscaped() {
        String awkward = "quote\" backslash\\ newline\n tab\t control" + (char) 1
                + " separator" + (char) 0x2028;
        String rendered = new Json.Writer().field("text", awkward).done();

        assertTrue(rendered.contains("\\\""), rendered);
        assertTrue(rendered.contains("\\\\"), rendered);
        assertTrue(rendered.contains("\\n"), rendered);
        assertTrue(rendered.contains("\\t"), rendered);
        assertTrue(rendered.contains("\\u0001"), rendered);
        // U+2028 is legal inside a JSON string and illegal inside a JavaScript string literal, so a
        // response containing one breaks any consumer that evaluates it.
        assertTrue(rendered.contains("\\u2028"), rendered);
        assertEquals(awkward, Json.string(Json.parseObject(rendered), "text"));
    }

    @Test
    void unicodeSurvives() {
        String rendered = new Json.Writer().field("note", "作者:张三 — é").done();
        assertEquals("作者:张三 — é", Json.string(Json.parseObject(rendered), "note"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"a\": 1,}",
        "{\"a\": [1, 2,]}",
        "{a: 1}",
        "{'a': 1}",
        "{\"a\": 1} trailing",
        "{\"a\": 1",
        "{\"a\": }",
        "{\"a\" 1}",
        "{\"a\": 01}",
        "{\"a\": 1.5}",
        "{\"a\": NaN}",
        "{\"a\": Infinity}",
        "{\"a\": undefined}",
        "{\"a\": \"unterminated}",
        "{\"a\": \"bad escape \\q\"}",
        "{\"a\": \"truncated \\u12\"}",
        "{\"a\": 1, \"a\": 2}",
        "[1, 2, 3]",
        "\"just a string\"",
        "42",
        "",
        "   ",
        "//comment\n{}",
        "{\"a\": 99999999999999999999999}",
    })
    void malformedOrUnsupportedInputIsRefused(String body) {
        assertThrows(Json.MalformedException.class, () -> Json.parseObject(body));
    }

    @Test
    void anUnescapedControlCharacterInsideAStringIsRefused() {
        String body = "{\"a\": \"x" + (char) 9 + "y\"}";
        Json.MalformedException refused =
                assertThrows(Json.MalformedException.class, () -> Json.parseObject(body));
        assertTrue(refused.getMessage().contains("control character"), refused.getMessage());
    }

    @Test
    void duplicateKeysAreRefusedRatherThanResolved() {
        // Legal JSON, and a security hazard: two parsers can disagree about which value wins, so
        // one request means different things to a proxy and to the service behind it.
        Json.MalformedException refused = assertThrows(Json.MalformedException.class,
                () -> Json.parseObject("{\"artifact\": \"a\", \"artifact\": \"b\"}"));
        assertTrue(refused.getMessage().contains("duplicate key"), refused.getMessage());
    }

    @Test
    void deepNestingIsRefusedBeforeItBecomesAStackOverflow() {
        StringBuilder deep = new StringBuilder("{\"a\":");
        for (int i = 0; i < 200; i++) {
            deep.append("[");
        }
        Json.MalformedException refused =
                assertThrows(Json.MalformedException.class, () -> Json.parseObject(deep.toString()));
        assertTrue(refused.getMessage().contains("nesting"), refused.getMessage());
    }

    @Test
    void anOversizedDocumentIsRefusedBeforeItIsParsed() {
        String huge = "{\"a\":\"" + "x".repeat(Json.MAX_BYTES) + "\"}";
        Json.MalformedException refused =
                assertThrows(Json.MalformedException.class, () -> Json.parseObject(huge));
        assertTrue(refused.getMessage().contains("over the"), refused.getMessage());
    }

    @Test
    void aMissingRequiredFieldSaysWhichOne() {
        Json.MalformedException refused = assertThrows(Json.MalformedException.class,
                () -> Json.string(Json.parseObject("{}"), "artifact"));
        assertTrue(refused.getMessage().contains("'artifact' is required"), refused.getMessage());
    }

    @Test
    void aFieldOfTheWrongTypeSaysSo() {
        Map<String, Object> body = Json.parseObject("{\"artifact\": 5, \"parents\": \"a\"}");
        assertTrue(assertThrows(Json.MalformedException.class,
                () -> Json.string(body, "artifact")).getMessage().contains("must be a string"));
        assertTrue(assertThrows(Json.MalformedException.class,
                () -> Json.strings(body, "parents")).getMessage().contains("must be an array"));
    }

    @Test
    void anArrayOfMixedTypesIsRefused() {
        Map<String, Object> body = Json.parseObject("{\"parents\": [\"a\", 2]}");
        assertThrows(Json.MalformedException.class, () -> Json.strings(body, "parents"));
    }

    @Test
    void labelsMustMapStringsToStrings() {
        Map<String, Object> body = Json.parseObject("{\"labels\": {\"a\": 1}}");
        assertThrows(Json.MalformedException.class, () -> Json.stringMap(body, "labels"));
    }

    @Test
    void anEmptyObjectAndAnEmptyArrayAreFine() {
        assertEquals(Map.of(), Json.parseObject("{}"));
        assertEquals(List.of(), Json.strings(Json.parseObject("{\"p\": []}"), "p"));
        assertEquals(Map.of(), Json.stringMap(Json.parseObject("{\"l\": {}}"), "l"));
    }

    @Test
    void whitespaceIsExactlyTheFourCharactersTheSpecificationAllows() {
        assertEquals(Map.of("a", 1L), Json.parseObject(" \t\r\n{ \"a\" : 1 } \n"));
        // A vertical tab is whitespace in most languages and not in JSON. Accepting it would mean
        // accepting documents nothing else will.
        assertThrows(Json.MalformedException.class,
                () -> Json.parseObject("{\"a\":" + (char) 11 + "1}"));
    }

    @Test
    void negativeAndLargeIntegersWork() {
        Map<String, Object> parsed =
                Json.parseObject("{\"a\": -1, \"b\": 0, \"c\": 9223372036854775807}");
        assertEquals(-1L, parsed.get("a"));
        assertEquals(0L, parsed.get("b"));
        assertEquals(Long.MAX_VALUE, parsed.get("c"));
    }
}
