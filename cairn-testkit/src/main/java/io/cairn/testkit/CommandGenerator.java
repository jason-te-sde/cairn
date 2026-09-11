package io.cairn.testkit;

import io.cairn.core.Command;
import io.cairn.core.Digest;
import io.cairn.core.ModelVersion;
import io.cairn.core.Ref;
import io.cairn.core.Stage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.SortedMap;

/**
 * A seeded stream of commands, shaped to reach the states that are hard to reason about.
 *
 * <p>A uniformly random generator over a large name space produces a run in which almost nothing
 * is accepted, so the registry stays empty and every invariant holds trivially. A generator that
 * only produces valid sequences never reaches a rejection, and rejection purity is one of the
 * properties under test. This one does neither: it keeps a weak memory of what it has <i>asked
 * for</i> — not of what succeeded — so it mostly builds on existing state while still colliding
 * with it constantly.
 *
 * <p>Specific shapes it is built to produce, because each one is a path that is easy to get wrong:
 *
 * <ul>
 *   <li><b>Republishing an existing version with a different artifact.</b> The immutability path.
 *   <li><b>Publishing against an artifact that was just collected.</b> The window in which a
 *       reference-counted delete could hand out a dangling pointer.
 *   <li><b>Artifacts shared by several versions.</b> The digest pool is small on purpose, so most
 *       artifacts end up with more than one reference and a delete that ignored the count would be
 *       caught rather than being caught only sometimes.
 *   <li><b>Deleting a version other versions descend from</b>, and deleting the production version.
 *   <li><b>Lineage several generations deep</b>, including across models.
 *   <li><b>Label values containing colons, slashes and non-ASCII text.</b> The exact inputs that
 *       corrupted a record in the delimiter-joined format this project replaces.
 * </ul>
 *
 * <p><b>What it deliberately does not produce is {@link Command.AckEffects}.</b> That is not an
 * omission, it is a finding. The generator used to emit them like any other command, and a
 * simulation run failed I7 with "effect #25 is acknowledged through 26 but was never applied
 * downstream" — because an acknowledgement from something that had not delivered anything moved
 * the watermark past effects nobody had sent, and the kernel had no way to know. So
 * {@code AckEffects} is a privileged command: exactly-once delivery holds only if the dispatcher is
 * the only thing that proposes it. The HTTP API has no route that reaches it, {@code SECURITY.md}
 * says so, and the simulator injects the two <i>legitimate</i> misbehaviours — a stale
 * acknowledgement and one ahead of the log — as faults instead.
 */
public final class CommandGenerator {

    private static final int MODELS = 4;
    private static final int VERSIONS_PER_MODEL = 6;

    /**
     * How many distinct artifacts exist.
     *
     * <p>Small relative to the number of versions, so sharing is the common case. A registry where
     * every version has its own artifact never exercises the interesting half of the delete path.
     */
    private static final int DIGESTS = 7;

    private static final String[] ACTORS = {"alice", "bob", "carol"};

    /**
     * Label values chosen to break a delimiter-based format.
     *
     * <p>Not decoration. The inherited defect was {@code split(":", 7)} over joined fields, and the
     * field that broke it in practice was a description holding a URL.
     */
    private static final String[] LABEL_VALUES = {
        "s3://bucket/prefix/key",
        "trained:2026-09-11T03:14:15Z",
        "auc=0.9131",
        "note: retrained after the incident",
        "作者:张三",
        "a,b;c:d|e",
    };

    private final Random random;
    private final List<Ref> asked = new ArrayList<>();
    private final List<Digest> ingested = new ArrayList<>();

    public CommandGenerator(long seed) {
        this.random = new Random(seed);
    }

    /** The next command. */
    public Command next(long step) {
        int choice = random.nextInt(100);
        if (ingested.isEmpty() || choice < 18) {
            return ingest();
        }
        if (choice < 55) {
            return publish(step);
        }
        if (choice < 78) {
            return promote(step);
        }
        return delete(step);
    }

    private Command ingest() {
        Digest digest = digest(random.nextInt(DIGESTS));
        if (!ingested.contains(digest)) {
            ingested.add(digest);
        }
        // The same digest always has the same size, except occasionally: a mismatch is a real
        // integrity signal and the rejection path for it needs to be reached.
        long size = 1024L * (1 + (digest.hex().charAt(0) % 16));
        if (random.nextInt(40) == 0) {
            size += 1;
        }
        return new Command.IngestBlob(digest, size);
    }

    private Command publish(long step) {
        Ref ref = randomRef();
        if (!asked.contains(ref)) {
            asked.add(ref);
        }
        // Picked from the whole pool rather than from what has been ingested, so that publishing
        // against something unavailable happens on its own rather than having to be arranged.
        Digest artifact = digest(random.nextInt(DIGESTS));
        List<Ref> parents = randomParents(ref);
        SortedMap<String, String> labels = random.nextInt(3) == 0 ? randomLabels() : null;
        return new Command.PublishVersion(
                ref, artifact, parents, labels, actor(), 1_700_000_000_000L + step);
    }

    private Command promote(long step) {
        return new Command.Promote(
                randomRef(),
                Stage.values()[random.nextInt(Stage.values().length)],
                actor(),
                1_700_000_000_000L + step);
    }

    private Command delete(long step) {
        return new Command.DeleteVersion(randomRef(), actor(), 1_700_000_000_000L + step);
    }

    /**
     * Picks a reference, usually one already in play.
     *
     * <p>Reusing names is what produces collisions — a republish with different content, a delete
     * of something another version descends from — and collisions are where the rules live.
     */
    private Ref randomRef() {
        if (!asked.isEmpty() && random.nextInt(100) < 70) {
            return asked.get(random.nextInt(asked.size()));
        }
        return Ref.of("m" + random.nextInt(MODELS), "1." + random.nextInt(VERSIONS_PER_MODEL) + ".0");
    }

    private List<Ref> randomParents(Ref self) {
        if (asked.isEmpty() || random.nextInt(100) < 45) {
            return List.of();
        }
        int count = 1 + random.nextInt(3);
        List<Ref> parents = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Ref candidate = asked.get(random.nextInt(asked.size()));
            // Self-parenting is generated on purpose, but rarely: it has its own rejection code and
            // the check for it must be reached.
            if (candidate.equals(self) && random.nextInt(8) != 0) {
                continue;
            }
            parents.add(candidate);
        }
        return parents;
    }

    private SortedMap<String, String> randomLabels() {
        int count = 1 + random.nextInt(3);
        List<String> pairs = new ArrayList<>(count * 2);
        for (int i = 0; i < count; i++) {
            pairs.add("label" + random.nextInt(5));
            pairs.add(LABEL_VALUES[random.nextInt(LABEL_VALUES.length)]);
        }
        return ModelVersion.labels(pairs.toArray(new String[0]));
    }

    private String actor() {
        return ACTORS[random.nextInt(ACTORS.length)];
    }

    /** The artifact bytes for a pool index, so a simulation can write them to a blob store. */
    public static byte[] contentFor(int index) {
        return ("artifact-" + index).getBytes(StandardCharsets.UTF_8);
    }

    /** The digest of pool entry {@code index}. */
    public static Digest digest(int index) {
        try {
            return Digest.ofSha256(
                    MessageDigest.getInstance("SHA-256").digest(contentFor(index)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** How many distinct artifacts the generator draws from. */
    public static int digestPoolSize() {
        return DIGESTS;
    }
}
