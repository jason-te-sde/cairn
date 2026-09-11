package io.cairn.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON, strictly.
 *
 * <p>Hand-written rather than taken from a library, and the reasoning is in
 * {@code docs/design/0006-no-framework.md}. The short version: this API has fifteen fields, a
 * general-purpose parser is built to accept what people send rather than what a specification
 * allows, and being strict is a property worth having at the edge of a system whose whole argument
 * is that it does not trust its input. The cost is this file, and a test suite that feeds it a
 * corpus of malformed documents.
 *
 * <p>What it refuses, on purpose: trailing commas, comments, unquoted keys, single quotes,
 * duplicate keys, {@code NaN}, leading zeros, control characters inside strings, nesting past a
 * depth limit, and documents past a size limit. What it does not support, because the API does not
 * need it: floating-point numbers. A number is a {@code long} or it is an error, which removes the
 * question of what happens to a version identifier that arrives as {@code 2.0}.
 */
public final class Json {

    /** Longest document the parser will accept, so a request cannot become an allocation. */
    static final int MAX_BYTES = 256 * 1024;

    /** Deepest nesting, so a document cannot become a stack overflow. */
    static final int MAX_DEPTH = 16;

    private Json() {}

    /** The input was not valid JSON, or was valid and not something this API accepts. */
    public static final class MalformedException extends RuntimeException {
        MalformedException(String message) {
            super(message);
        }
    }

    // ---- writing -----------------------------------------------------------------------------

    /** Builds an object, in insertion order. */
    public static final class Writer {

        private final StringBuilder out = new StringBuilder(128);
        private boolean first = true;

        public Writer() {
            out.append('{');
        }

        /** Adds a string field. A null value is written as {@code null}. */
        public Writer field(String name, String value) {
            separate(name);
            if (value == null) {
                out.append("null");
            } else {
                escape(out, value);
            }
            return this;
        }

        /** Adds a number field. */
        public Writer field(String name, long value) {
            separate(name);
            out.append(value);
            return this;
        }

        /** Adds a boolean field. */
        public Writer field(String name, boolean value) {
            separate(name);
            out.append(value);
            return this;
        }

        /** Adds a field whose value is already rendered JSON. */
        public Writer raw(String name, String json) {
            separate(name);
            out.append(json);
            return this;
        }

        /** Adds an array of already-rendered JSON values. */
        public Writer array(String name, List<String> values) {
            separate(name);
            out.append('[');
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(values.get(i));
            }
            out.append(']');
            return this;
        }

        /** Adds an array of strings. */
        public Writer strings(String name, List<String> values) {
            separate(name);
            out.append('[');
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                escape(out, values.get(i));
            }
            out.append(']');
            return this;
        }

        /** Adds a string-to-string map, keys in the order given. */
        public Writer object(String name, Map<String, String> entries) {
            separate(name);
            out.append('{');
            boolean firstEntry = true;
            for (var entry : entries.entrySet()) {
                if (!firstEntry) {
                    out.append(',');
                }
                firstEntry = false;
                escape(out, entry.getKey());
                out.append(':');
                escape(out, entry.getValue());
            }
            out.append('}');
            return this;
        }

        /** The finished object. */
        public String done() {
            return out.append('}').toString();
        }

        @Override
        public String toString() {
            return done();
        }

        private void separate(String name) {
            if (!first) {
                out.append(',');
            }
            first = false;
            escape(out, name);
            out.append(':');
        }
    }

    /** Renders an array of already-rendered values. */
    public static String array(List<String> values) {
        StringBuilder out = new StringBuilder(16 + values.size() * 32).append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(values.get(i));
        }
        return out.append(']').toString();
    }

    /**
     * Escapes a string into a JSON string literal.
     *
     * <p>Escapes every control character rather than only the ones with short forms, and escapes
     * {@code U+2028} and {@code U+2029} as well: both are legal in a JSON string and neither is
     * legal in a JavaScript string literal, so a response containing one breaks any consumer that
     * evaluates it. That is a real interoperability bug rather than a theoretical one.
     */
    static void escape(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ---- reading -----------------------------------------------------------------------------

    /** Parses a document that must be an object. */
    public static Map<String, Object> parseObject(String text) {
        if (text == null || text.isBlank()) {
            throw new MalformedException("the body is empty");
        }
        if (text.length() > MAX_BYTES) {
            throw new MalformedException(
                    "the body is " + text.length() + " characters, over the " + MAX_BYTES
                            + " limit");
        }
        Parser parser = new Parser(text);
        Object value = parser.value(0);
        parser.skipWhitespace();
        if (!parser.done()) {
            throw new MalformedException(
                    "trailing content after the document at offset " + parser.position);
        }
        if (!(value instanceof Map)) {
            throw new MalformedException("the body must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) value;
        return object;
    }

    /** A required string field. */
    public static String string(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (!(value instanceof String text)) {
            throw new MalformedException(
                    value == null
                            ? "'" + name + "' is required"
                            : "'" + name + "' must be a string");
        }
        return text;
    }

    /** An optional string field. */
    public static String stringOrNull(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new MalformedException("'" + name + "' must be a string");
        }
        return text;
    }

    /** An optional array-of-strings field, empty if absent. */
    public static List<String> strings(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new MalformedException("'" + name + "' must be an array of strings");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof String text)) {
                throw new MalformedException("'" + name + "' must contain only strings");
            }
            out.add(text);
        }
        return out;
    }

    /** An optional string-to-string object field, empty if absent. */
    public static Map<String, String> stringMap(Map<String, Object> object, String name) {
        Object value = object.get(name);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new MalformedException("'" + name + "' must be an object of strings");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getValue() instanceof String text)) {
                throw new MalformedException(
                        "'" + name + "' must map strings to strings; '" + entry.getKey()
                                + "' is not a string");
            }
            out.put((String) entry.getKey(), text);
        }
        return out;
    }

    /** The strict parser. */
    private static final class Parser {

        private final String text;
        private int position;

        Parser(String text) {
            this.text = text;
        }

        boolean done() {
            return position >= text.length();
        }

        void skipWhitespace() {
            while (position < text.length()) {
                char c = text.charAt(position);
                // Exactly the four characters the specification allows. A parser that also skips a
                // vertical tab is a parser that accepts documents nothing else will.
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    position++;
                } else {
                    return;
                }
            }
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw new MalformedException("nesting deeper than " + MAX_DEPTH);
            }
            skipWhitespace();
            if (done()) {
                throw new MalformedException("the document ended where a value was expected");
            }
            char c = text.charAt(position);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object(int depth) {
            expect('{');
            Map<String, Object> out = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return out;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw new MalformedException(
                            "object keys must be quoted strings, at offset " + position);
                }
                String key = string();
                skipWhitespace();
                expect(':');
                Object value = value(depth + 1);
                if (out.put(key, value) != null) {
                    // Duplicate keys are legal JSON and a security hazard: two parsers can disagree
                    // about which one wins, which is how a request means one thing to a proxy and
                    // another to the service behind it.
                    throw new MalformedException("duplicate key '" + key + "'");
                }
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    position++;
                    skipWhitespace();
                    if (peek() == '}') {
                        throw new MalformedException("trailing comma at offset " + position);
                    }
                    continue;
                }
                if (next == '}') {
                    position++;
                    return out;
                }
                throw new MalformedException("expected ',' or '}' at offset " + position);
            }
        }

        private List<Object> array(int depth) {
            expect('[');
            List<Object> out = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                position++;
                return out;
            }
            while (true) {
                out.add(value(depth + 1));
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    position++;
                    skipWhitespace();
                    if (peek() == ']') {
                        throw new MalformedException("trailing comma at offset " + position);
                    }
                    continue;
                }
                if (next == ']') {
                    position++;
                    return out;
                }
                throw new MalformedException("expected ',' or ']' at offset " + position);
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (done()) {
                    throw new MalformedException("unterminated string");
                }
                char c = text.charAt(position++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    out.append(unescape());
                    continue;
                }
                if (c < 0x20) {
                    throw new MalformedException(
                            "unescaped control character U+" + String.format("%04X", (int) c)
                                    + " in a string at offset " + (position - 1));
                }
                out.append(c);
            }
        }

        private char unescape() {
            if (done()) {
                throw new MalformedException("the document ended inside an escape");
            }
            char c = text.charAt(position++);
            return switch (c) {
                case '"' -> '"';
                case '\\' -> '\\';
                case '/' -> '/';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> {
                    if (position + 4 > text.length()) {
                        throw new MalformedException("truncated \\u escape");
                    }
                    String hex = text.substring(position, position + 4);
                    position += 4;
                    try {
                        yield (char) Integer.parseInt(hex, 16);
                    } catch (NumberFormatException e) {
                        throw new MalformedException("'" + hex + "' is not a \\u escape");
                    }
                }
                default -> throw new MalformedException("unknown escape '\\" + c + "'");
            };
        }

        private Object literal(String expected, Object value) {
            if (!text.startsWith(expected, position)) {
                throw new MalformedException("unexpected value at offset " + position);
            }
            position += expected.length();
            return value;
        }

        private Long number() {
            int start = position;
            if (peek() == '-') {
                position++;
            }
            int digitsStart = position;
            while (position < text.length() && Character.isDigit(text.charAt(position))) {
                position++;
            }
            if (position == digitsStart) {
                throw new MalformedException("unexpected value at offset " + start);
            }
            if (position - digitsStart > 1 && text.charAt(digitsStart) == '0') {
                throw new MalformedException("leading zero in a number at offset " + digitsStart);
            }
            if (position < text.length()
                    && (text.charAt(position) == '.'
                            || text.charAt(position) == 'e'
                            || text.charAt(position) == 'E')) {
                // Deliberate. This API has no floating-point field, and accepting one would raise
                // the question of what a version of 2.0 means.
                throw new MalformedException(
                        "this API has no floating-point fields, at offset " + position);
            }
            try {
                return Long.parseLong(text.substring(start, position));
            } catch (NumberFormatException e) {
                throw new MalformedException(
                        "'" + text.substring(start, position) + "' does not fit in a 64-bit"
                                + " integer");
            }
        }

        private char peek() {
            if (done()) {
                throw new MalformedException("the document ended unexpectedly");
            }
            return text.charAt(position);
        }

        private void expect(char c) {
            if (done() || text.charAt(position) != c) {
                throw new MalformedException(
                        "expected '" + c + "' at offset " + position);
            }
            position++;
        }
    }
}
