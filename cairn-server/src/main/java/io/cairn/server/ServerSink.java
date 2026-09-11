package io.cairn.server;

import io.cairn.core.Effect;
import io.cairn.core.SequencedEffect;
import io.cairn.store.BlobStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default consumer: it collects artifacts, and it writes every effect somewhere a real consumer
 * can read.
 *
 * <p>Collecting is the one effect the registry cannot delegate, because deleting bytes is the whole
 * reason the reference counting exists. Everything else is somebody else's integration, and rather
 * than ship a Kafka client this appends effects to a file as JSON lines. That is not a placeholder:
 * a JSON-lines file with a monotonic sequence number in every record is a queue that any consumer
 * can tail and deduplicate against, and it makes the sequence number — the thing that makes
 * exactly-once possible — visible to whoever is integrating.
 *
 * <p>Order matters in {@link #deliver}: the artifact is deleted <b>before</b> the effect is
 * recorded as delivered, because the caller's {@link io.cairn.effects.IdempotentSink} advances its
 * watermark only when this returns. A sink that recorded first would lose a collection order on a
 * crash, and an artifact that is never collected is a leak nobody notices.
 */
public final class ServerSink implements io.cairn.effects.EffectSink {

    private static final Logger LOG = LoggerFactory.getLogger(ServerSink.class);

    private final BlobStore blobs;
    private final Path effectsLog;

    public ServerSink(BlobStore blobs, Path effectsLog) {
        this.blobs = blobs;
        this.effectsLog = effectsLog;
    }

    @Override
    public void deliver(SequencedEffect sequenced) {
        if (sequenced.effect() instanceof Effect.ArtifactCollected collected) {
            boolean removed = blobs.delete(collected.digest());
            LOG.info("collected artifact {} ({} bytes){}",
                    collected.digest().shortHex(), collected.size(),
                    removed ? "" : " (already gone)");
        }
        if (effectsLog != null) {
            append(Api.renderEffect(sequenced));
        }
    }

    private void append(String line) {
        try {
            Files.createDirectories(effectsLog.toAbsolutePath().getParent());
            Files.writeString(effectsLog, line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Throwing is correct: the dispatcher will not acknowledge, the effect stays in the
            // outbox, and it is retried. Swallowing this would be the inherited defect in a new
            // costume — a side effect that failed while the state change that caused it stayed.
            throw new UncheckedIOException("cannot append to " + effectsLog, e);
        }
    }

    @Override
    public String name() {
        return effectsLog == null ? "collector" : "collector+" + effectsLog.getFileName();
    }
}
