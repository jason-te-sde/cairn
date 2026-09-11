package io.cairn.testkit;

/**
 * {@link Flaw#COLON_CODEC}: the record format this project exists to replace, runnable.
 *
 * <p>A faithful reproduction, field for field. The original stored a model version as
 *
 * <pre>
 * modelId + ":" + version + ":" + s3Bucket + ":" + s3Key + ":" + fileHash + ":" + fileSize
 *         + ":" + description
 * </pre>
 *
 * <p>and read it back with {@code split(":", 7)}. Two distinct failures follow, and the second one
 * is worse than the first.
 *
 * <p><b>A colon in a middle field shifts every field after it.</b> {@code s3Key} is field four of
 * seven, and object keys contain colons routinely — {@code models/fraud/2.1.0:final/model.pt} is an
 * ordinary key. One colon there and {@code fileHash} holds the tail of the key,
 * {@code Long.parseLong(fileSize)} is handed a hex string, and the whole record fails to parse. In
 * the original that exception was caught and logged inside the state machine's apply method, so the
 * command was silently dropped — on that replica only, while every other replica applied it.
 *
 * <p><b>The read path joined eight fields and split seven.</b> The original's {@code get} appended
 * {@code createdAt} to the same string and handed it to the same {@code split(":", 7)}, so
 * {@code description} came back as {@code description + ":" + createdAt}. Nothing threw. The record
 * was simply wrong, every time, for every model, and a reader had no way to tell.
 *
 * <p>Kept in main code rather than in a test because {@code ColonCodecTest} demonstrates both and
 * {@code docs/design/0002-canonical-encoding.md} points at it. A design note that says "the old
 * format was broken" is an assertion; one that points at a runnable reproduction is an argument.
 */
public final class ColonCodec {

    private ColonCodec() {}

    /**
     * The original's record, field for field.
     *
     * @param modelId the model
     * @param version the version
     * @param s3Bucket the bucket the artifact is in
     * @param s3Key the object key, which is where the colons come from
     * @param fileHash a digest the original never verified
     * @param fileSize the length
     * @param description free text
     * @param createdAt when it was registered
     */
    public record Original(
            String modelId,
            String version,
            String s3Bucket,
            String s3Key,
            String fileHash,
            long fileSize,
            String description,
            long createdAt) {

        /** A well-formed record, so a test reads as a comparison rather than as a setup. */
        public static Original sample(String s3Key, String description) {
            return new Original(
                    "fraud", "2.1.0", "models", s3Key,
                    "d1e8a70b5ccab1dc2f56bbf7e99f064a660c08e361a35751b9c483c88943d082",
                    4_194_304L, description, 1_700_000_000_000L);
        }
    }

    /** Joins the seven fields the original wrote on the apply path. */
    public static String encodeForApply(Original record) {
        return record.modelId()
                + ":" + record.version()
                + ":" + record.s3Bucket()
                + ":" + record.s3Key()
                + ":" + record.fileHash()
                + ":" + record.fileSize()
                + ":" + record.description();
    }

    /**
     * Joins the eight fields the original's read path wrote, for the same seven-way split.
     *
     * <p>The off-by-one is the point and it is reproduced exactly: {@code createdAt} is appended to
     * a string that is then split into seven parts.
     */
    public static String encodeForRead(Original record) {
        return encodeForApply(record) + ":" + record.createdAt();
    }

    /**
     * {@code split(":", 7)}, and then the original's field assignment.
     *
     * @throws IllegalArgumentException if there are fewer than seven fields
     * @throws NumberFormatException if the size field is not a number, which is what a shifted
     *     record looks like — and what the original caught and logged
     */
    public static Original decode(String encoded) {
        String[] parts = encoded.split(":", 7);
        if (parts.length < 7) {
            throw new IllegalArgumentException(
                    "expected 7 fields, got " + parts.length + ": " + encoded);
        }
        return new Original(
                parts[0], parts[1], parts[2], parts[3], parts[4],
                Long.parseLong(parts[5]),
                parts[6],
                0L);
    }

    /**
     * Decodes without parsing the size, so a shifted record can be inspected rather than thrown.
     *
     * @return the seven fields as strings, in the order the original assigned them
     */
    public static String[] decodeFields(String encoded) {
        String[] parts = encoded.split(":", 7);
        if (parts.length < 7) {
            throw new IllegalArgumentException(
                    "expected 7 fields, got " + parts.length + ": " + encoded);
        }
        return parts;
    }
}
