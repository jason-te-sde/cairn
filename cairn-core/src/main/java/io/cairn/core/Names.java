package io.cairn.core;

/**
 * The character rules for identifiers, and the reasoning for each.
 *
 * <p>This class exists because the registry this one is derived from stored a version record as
 * {@code modelId + ":" + version + ":" + bucket + ...} and read it back with
 * {@code split(":", 7)}. Every identifier below is therefore checked against a character set on
 * construction rather than trusted, and the encoding is length-prefixed rather than delimited, so
 * the two defences are independent: a name cannot contain a delimiter, and there is no delimiter
 * for it to contain.
 */
final class Names {

    private Names() {}

    /**
     * Model identifiers are lowercase.
     *
     * <p>Not a style preference. A registry that accepts {@code Sentiment} and {@code sentiment} as
     * different models will eventually serve one when someone meant the other, and the two will
     * have different production versions. Container registries settled on lowercase for the same
     * reason.
     */
    static String modelId(String raw) {
        require(raw != null, "model id must not be null");
        require(!raw.isEmpty(), "model id must not be empty");
        require(raw.length() <= Limits.MAX_MODEL_ID,
                "model id must be at most " + Limits.MAX_MODEL_ID + " characters: " + raw.length());
        require(isAlnumLower(raw.charAt(0)) && isAlnumLower(raw.charAt(raw.length() - 1)),
                "model id must start and end with a lowercase letter or digit: " + quote(raw));
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            require(isAlnumLower(c) || c == '.' || c == '_' || c == '-',
                    "model id may only contain a-z, 0-9, '.', '_' and '-': " + quote(raw));
        }
        return raw;
    }

    /**
     * Version identifiers keep their case, because {@code 1.0.0-RC1} and {@code 1.0.0+sha.abc} are
     * how people write versions and mangling them would be worse than accepting them.
     *
     * <p>No ordering is inferred from the characters. See {@code docs/design/0005-scope.md}: the
     * registry never guesses which version is newer, it records which one is in production.
     */
    static String versionId(String raw) {
        require(raw != null, "version must not be null");
        require(!raw.isEmpty(), "version must not be empty");
        require(raw.length() <= Limits.MAX_VERSION_ID,
                "version must be at most " + Limits.MAX_VERSION_ID + " characters: " + raw.length());
        require(isAlnum(raw.charAt(0)) && isAlnum(raw.charAt(raw.length() - 1)),
                "version must start and end with a letter or digit: " + quote(raw));
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            require(isAlnum(c) || c == '.' || c == '_' || c == '-' || c == '+',
                    "version may only contain A-Z, a-z, 0-9, '.', '_', '-' and '+': " + quote(raw));
        }
        return raw;
    }

    /** Label keys follow the model-id rules plus {@code /}, so a namespace prefix is expressible. */
    static String labelKey(String raw) {
        require(raw != null, "label key must not be null");
        require(!raw.isEmpty(), "label key must not be empty");
        require(raw.length() <= Limits.MAX_LABEL_KEY,
                "label key must be at most " + Limits.MAX_LABEL_KEY + " characters: " + raw.length());
        require(isAlnumLower(raw.charAt(0)) && isAlnumLower(raw.charAt(raw.length() - 1)),
                "label key must start and end with a lowercase letter or digit: " + quote(raw));
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            require(isAlnumLower(c) || c == '.' || c == '_' || c == '-' || c == '/',
                    "label key may only contain a-z, 0-9, '.', '_', '-' and '/': " + quote(raw));
        }
        return raw;
    }

    /**
     * Label values are arbitrary text, minus control characters.
     *
     * <p>A value is data, so restricting its alphabet would be the registry having an opinion about
     * somebody else's metadata. Control characters are excluded anyway: they survive the codec
     * perfectly well and then corrupt every log line and terminal that prints them.
     */
    static String labelValue(String raw) {
        require(raw != null, "label value must not be null");
        require(raw.length() <= Limits.MAX_LABEL_VALUE,
                "label value must be at most " + Limits.MAX_LABEL_VALUE + " characters: " + raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            require(!Character.isISOControl(c),
                    "label value must not contain control characters: U+"
                            + String.format("%04X", (int) c));
        }
        return raw;
    }

    /** Who performed a mutation. Recorded verbatim in the log, so the same rule as a label value. */
    static String actor(String raw) {
        require(raw != null, "actor must not be null");
        require(!raw.isEmpty(), "actor must not be empty");
        require(raw.length() <= Limits.MAX_ACTOR,
                "actor must be at most " + Limits.MAX_ACTOR + " characters: " + raw.length());
        for (int i = 0; i < raw.length(); i++) {
            require(!Character.isISOControl(raw.charAt(i)),
                    "actor must not contain control characters");
        }
        return raw;
    }

    private static boolean isAlnumLower(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static boolean isAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /**
     * Renders a rejected name for an error message without letting it break the message.
     *
     * <p>A validation failure is the one place a hostile string is guaranteed to reach a log, so it
     * is escaped here rather than interpolated raw.
     */
    private static String quote(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 2).append('\'');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isISOControl(c) || c == '\'' || c == '\\') {
                out.append(String.format("\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('\'').toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
