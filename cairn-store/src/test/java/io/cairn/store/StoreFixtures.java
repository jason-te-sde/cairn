package io.cairn.store;

import io.cairn.core.Command;
import io.cairn.core.Digest;
import io.cairn.core.Ref;
import io.cairn.core.Stage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Commands and digests the store tests share. */
final class StoreFixtures {

    private StoreFixtures() {}

    static Digest digest(int n) {
        try {
            return Digest.ofSha256(MessageDigest.getInstance("SHA-256")
                    .digest(("artifact-" + n).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * A run of commands spread over several models, each with a bounded number of versions.
     *
     * <p>The distribution matters to anything measuring throughput. A script that publishes ten
     * thousand versions of one model measures the cost of copying a version map, not the cost of
     * the log; {@code script} below does exactly that and is fine for correctness tests, where the
     * shape is the point rather than the speed.
     *
     * @param commands roughly how many commands to produce
     * @param models how many distinct models
     * @param versionsPerModel how many versions each model gets before names are reused
     */
    static List<Command> spread(int commands, int models, int versionsPerModel) {
        List<Command> out = new ArrayList<>(commands * 2);
        for (int i = 0; i < commands; i++) {
            out.add(new Command.IngestBlob(digest(i % 64), 1024L * (1 + i % 17)));
            out.add(Command.PublishVersion.of(
                    Ref.of("m" + (i % models), "1.0." + (i % versionsPerModel)),
                    digest(i % 64), "alice", 1_000L + i));
        }
        return out;
    }

    /** A run of commands long enough to cross a segment boundary if one is set small. */
    static List<Command> script(int versions) {
        List<Command> commands = new ArrayList<>();
        for (int i = 0; i < versions; i++) {
            commands.add(new Command.IngestBlob(digest(i), 1024L * (i + 1)));
            commands.add(Command.PublishVersion.of(
                    Ref.of("fraud", "1.0." + i), digest(i), "alice", 1_000L + i));
        }
        if (versions > 0) {
            commands.add(new Command.Promote(
                    Ref.of("fraud", "1.0.0"), Stage.PRODUCTION, "alice", 9_000L));
        }
        return commands;
    }
}
