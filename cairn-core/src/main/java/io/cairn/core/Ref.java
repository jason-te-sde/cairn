package io.cairn.core;

/**
 * A pointer to one version of one model.
 *
 * <p>Rendered as {@code model@version} rather than {@code model:version}, which is not decoration:
 * a colon is the character the format this project replaces used as its field separator, and using
 * it here would put it back into every log line and error message where a reader could mistake it
 * for structure.
 *
 * @param model the model
 * @param version the version within that model
 */
public record Ref(ModelId model, VersionId version) implements Comparable<Ref> {

    public Ref {
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
    }

    /** Builds a reference from raw strings, validating both. */
    public static Ref of(String model, String version) {
        return new Ref(ModelId.of(model), VersionId.of(version));
    }

    /**
     * Parses {@code model@version}.
     *
     * @throws IllegalArgumentException if there is no {@code @}, or either half is invalid
     */
    public static Ref parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("reference must not be null");
        }
        int at = text.indexOf('@');
        if (at < 0) {
            throw new IllegalArgumentException("reference must be model@version: '" + text + "'");
        }
        // lastIndexOf would be wrong in the other direction, but neither half may contain '@' at
        // all, so a second one is a malformed reference rather than a parsing choice.
        if (text.indexOf('@', at + 1) >= 0) {
            throw new IllegalArgumentException("reference must contain one '@': '" + text + "'");
        }
        return of(text.substring(0, at), text.substring(at + 1));
    }

    @Override
    public int compareTo(Ref other) {
        int byModel = model.compareTo(other.model);
        return byModel != 0 ? byModel : version.compareTo(other.version);
    }

    @Override
    public String toString() {
        return model + "@" + version;
    }
}
