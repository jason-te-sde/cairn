package io.cairn.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Values the tests in this package keep needing. */
final class Fixtures {

    static final String ACTOR = "tester";

    private Fixtures() {}

    /** A distinct, valid digest per integer, so a test can say "a different artifact" in one word. */
    static Digest digest(int n) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return Digest.ofSha256(sha.digest(("artifact-" + n).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static Ref ref(String model, String version) {
        return Ref.of(model, version);
    }

    /** Applies a run of commands from index 1, asserting each was accepted. */
    static Registry applyAll(Command... commands) {
        Registry state = Registry.empty();
        long index = 1;
        for (Command command : commands) {
            Transition transition = Kernel.apply(state, index++, command);
            if (!transition.outcome().accepted()) {
                throw new AssertionError(
                        "fixture command was rejected: " + command + " -> " + transition.outcome());
            }
            state = transition.state();
        }
        return state;
    }

    static Command.IngestBlob ingest(Digest digest, long size) {
        return new Command.IngestBlob(digest, size);
    }

    static Command.PublishVersion publish(Ref ref, Digest artifact) {
        return Command.PublishVersion.of(ref, artifact, ACTOR, 1_700_000_000_000L);
    }

    static Command.PublishVersion publish(Ref ref, Digest artifact, List<Ref> parents) {
        return new Command.PublishVersion(
                ref, artifact, parents, null, ACTOR, 1_700_000_000_000L);
    }

    static Command.Promote promote(Ref ref, Stage target) {
        return new Command.Promote(ref, target, ACTOR, 1_700_000_000_001L);
    }

    static Command.DeleteVersion delete(Ref ref) {
        return new Command.DeleteVersion(ref, ACTOR, 1_700_000_000_002L);
    }
}
