package io.cairn.core;

import java.util.List;
import java.util.SortedMap;

/**
 * Everything that can change the registry. Five of them.
 *
 * <p>Two details are worth noticing before reading the individual commands.
 *
 * <p><b>Time is an argument.</b> Every command that records a timestamp carries it, rather than the
 * kernel calling a clock. That is what makes a replay produce the state it produced the first time
 * — a kernel that read {@code System.currentTimeMillis()} would write a different value on every
 * replica and a different one again after a restart, and the replicas would have diverged in a
 * field nobody thinks of as state.
 *
 * <p><b>{@link AckEffects} is a command.</b> Acknowledging delivery is a change to replicated
 * state, not a local note, because the whole exactly-once argument rests on the watermark surviving
 * the crash of whatever moved it. A dispatcher that advanced a local counter and then died would
 * replay effects it had already delivered — which is tolerable — or, if it advanced the counter
 * before delivering, would skip them, which is not.
 */
public sealed interface Command {

    /**
     * Record that an artifact exists, having verified it.
     *
     * <p>The verification is the caller's job and is not optional: {@code cairn-store} computes the
     * digest while streaming the bytes to disk and only then is this command proposed. The registry
     * this project is derived from took a {@code fileHash} field from the client and stored it
     * without ever hashing the file, which means its integrity guarantee was "the uploader did not
     * lie".
     *
     * <p>Idempotent in the digest: ingesting the same artifact twice is a success that changes
     * nothing. The same digest with a different length is {@link RejectionCode#DIGEST_SIZE_MISMATCH},
     * because SHA-256 does not collide by accident and something upstream is broken.
     *
     * @param digest the verified content address
     * @param size the measured length in bytes
     */
    record IngestBlob(Digest digest, long size) implements Command {
        public IngestBlob {
            if (digest == null) {
                throw new IllegalArgumentException("digest must not be null");
            }
            if (size < 0 || size > Limits.MAX_ARTIFACT_BYTES) {
                throw new IllegalArgumentException("size out of range: " + size);
            }
        }
    }

    /**
     * Create a version, in {@link Stage#STAGING}.
     *
     * <p>Versions arrive staged, never in production. Publishing and releasing are separate
     * decisions, and a registry where an upload can become the thing serving traffic is a registry
     * where a typo can.
     *
     * @param ref which model and version
     * @param artifact the content address, which must already have been ingested
     * @param parents what it was derived from; each must exist and be live
     * @param labels free-form metadata
     * @param publishedBy who is publishing
     * @param publishedAt the timestamp to record
     */
    record PublishVersion(
            Ref ref,
            Digest artifact,
            List<Ref> parents,
            SortedMap<String, String> labels,
            String publishedBy,
            long publishedAt) implements Command {

        public PublishVersion {
            if (ref == null) {
                throw new IllegalArgumentException("ref must not be null");
            }
            if (artifact == null) {
                throw new IllegalArgumentException("artifact must not be null");
            }
            Names.actor(publishedBy);
            // Normalized here, with the same helpers the stored version uses. A command is what
            // goes in the log, so making it canonical at construction means two clients sending
            // the same publish with their parents in a different order produce identical log
            // bytes, and the idempotency check on republish compares equal instead of conflicting.
            parents = ModelVersion.normalizeParents(parents);
            labels = ModelVersion.normalizeLabels(labels);
        }

        /** A publish with no lineage and no labels, which is most of them. */
        public static PublishVersion of(Ref ref, Digest artifact, String by, long at) {
            return new PublishVersion(ref, artifact, List.of(), null, by, at);
        }
    }

    /**
     * Move a version to another stage.
     *
     * <p>Promotion to {@link Stage#PRODUCTION} demotes the incumbent to {@link Stage#ARCHIVED} in
     * the same transition. That is the only reason "at most one production version" is a property
     * rather than a hope: as two commands it would be two log entries, and between them the
     * registry would hold two production versions, or none, depending on which order somebody chose
     * and whether the process survived long enough to send the second.
     *
     * @param ref which version
     * @param target where it should end up
     * @param actor who is moving it
     * @param atMillis the timestamp to record
     */
    record Promote(Ref ref, Stage target, String actor, long atMillis) implements Command {
        public Promote {
            if (ref == null) {
                throw new IllegalArgumentException("ref must not be null");
            }
            if (target == null) {
                throw new IllegalArgumentException("target must not be null");
            }
            Names.actor(actor);
        }
    }

    /**
     * Tombstone a version, releasing its artifact if nothing else holds it.
     *
     * <p>Refused for a production version and for one with live descendants. Both refusals are
     * about not creating a dangling reference: the first would leave serving infrastructure loading
     * something that has been deleted, the second would leave another version's provenance
     * pointing at nothing.
     *
     * @param ref which version
     * @param actor who is deleting it
     * @param atMillis the timestamp to record
     */
    record DeleteVersion(Ref ref, String actor, long atMillis) implements Command {
        public DeleteVersion {
            if (ref == null) {
                throw new IllegalArgumentException("ref must not be null");
            }
            Names.actor(actor);
        }
    }

    /**
     * Advance the dispatch watermark: every effect up to {@code throughSeq} has been delivered
     * and durably applied downstream.
     *
     * <p>Monotone and idempotent. Acknowledging a sequence number at or below the current watermark
     * is an accepted no-op, which is what a redelivered acknowledgement looks like. Acknowledging
     * one the kernel has never assigned is {@link RejectionCode#ACK_AHEAD_OF_LOG} rather than a
     * clamp, because it means the dispatcher and the registry disagree about which registry this
     * is.
     *
     * @param throughSeq the highest sequence number known to be applied downstream
     */
    record AckEffects(long throughSeq) implements Command {
        public AckEffects {
            if (throughSeq < 0) {
                throw new IllegalArgumentException("throughSeq must not be negative: " + throughSeq);
            }
        }
    }
}
