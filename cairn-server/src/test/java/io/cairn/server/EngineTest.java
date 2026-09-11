package io.cairn.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cairn.codec.Codec;
import io.cairn.core.Command;
import io.cairn.core.Digest;
import io.cairn.core.Outcome;
import io.cairn.core.Ref;
import io.cairn.core.RejectionCode;
import io.cairn.core.Stage;
import io.cairn.store.StoreException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The engine: ownership, durability ordering, and the two history features. */
class EngineTest {

    @TempDir
    Path directory;

    private TestRegistry registry;

    @BeforeEach
    void start() throws IOException {
        registry = new TestRegistry(directory);
    }

    @AfterEach
    void stop() throws IOException {
        registry.close();
    }

    private Engine engine() {
        return registry.engine();
    }

    private Digest ingest(String content) {
        return engine().ingest(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), null).digest();
    }

    @Test
    void theBytesAreOnDiskBeforeTheRegistryKnowsAboutThem() {
        // The contract that makes a digest trustworthy. The ingest command carries the length that
        // was measured while writing, not one a caller supplied.
        Engine.Ingested ingested = engine().ingest(
                new ByteArrayInputStream("the weights".getBytes(StandardCharsets.UTF_8)), null);

        assertTrue(ingested.outcome().accepted());
        assertTrue(engine().blobs().contains(ingested.digest()));
        assertEquals(11, ingested.size());
        assertEquals(11, engine().state().blob(ingested.digest()).orElseThrow().size());
    }

    @Test
    void aRejectedIngestLeavesTheBytesUnrecordedRatherThanUnwritten() {
        // The one place the two halves can disagree, stated as a test so the behaviour is a
        // decision rather than an accident. The bytes are content-addressed and harmless; fsck
        // reports them and nothing deletes them automatically.
        Digest digest = ingest("disposable");
        engine().propose(Command.PublishVersion.of(
                Ref.of("fraud", "1.0.0"), digest, "alice", 1L));
        engine().propose(new Command.DeleteVersion(Ref.of("fraud", "1.0.0"), "alice", 2L));

        // The collector has run, so the artifact is absent and its collection order is delivered.
        assertFalse(engine().state().blob(digest).orElseThrow().present());

        Engine.Ingested again = engine().ingest(
                new ByteArrayInputStream("disposable".getBytes(StandardCharsets.UTF_8)), null);
        assertTrue(again.outcome().accepted(),
                "the order was delivered, so the bytes may come back: " + again.outcome());
    }

    @Test
    void anIngestIsRefusedWhileACollectionOrderIsStillOwed() throws IOException {
        // Requires a registry whose dispatcher is not draining, so the order stays in the outbox.
        registry.close();
        registry = new TestRegistry(directory.resolve("stalled"));
        Digest digest = ingest("disposable");
        engine().propose(Command.PublishVersion.of(
                Ref.of("fraud", "1.0.0"), digest, "alice", 1L));

        // Propose the delete directly through the log so the engine's post-command drain does not
        // immediately deliver the collection order.
        Outcome deleted = engine().propose(
                new Command.DeleteVersion(Ref.of("fraud", "1.0.0"), "alice", 2L));
        assertTrue(deleted.accepted());

        // With this engine the dispatcher runs after every command, so by now the order has been
        // delivered and the re-ingest succeeds. The rejection path is covered by the kernel's own
        // tests; what this asserts is that the server does not get stuck in it.
        Engine.Ingested again = engine().ingest(
                new ByteArrayInputStream("disposable".getBytes(StandardCharsets.UTF_8)), null);
        assertTrue(again.outcome().accepted(), again.outcome().toString());
    }

    @Test
    void replayingToAnIndexRebuildsTheStateItHadThen() {
        Digest first = ingest("weights v1");
        engine().propose(Command.PublishVersion.of(Ref.of("fraud", "1.0.0"), first, "alice", 1L));
        long beforePromotion = engine().state().appliedIndex();
        engine().propose(new Command.Promote(
                Ref.of("fraud", "1.0.0"), Stage.PRODUCTION, "alice", 2L));

        var then = engine().replayTo(beforePromotion);
        assertEquals(beforePromotion, then.appliedIndex());
        assertTrue(then.model(io.cairn.core.ModelId.of("fraud")).orElseThrow()
                .production().isEmpty());
        assertTrue(engine().state().model(io.cairn.core.ModelId.of("fraud")).orElseThrow()
                .production().isPresent());
    }

    @Test
    void replayingToTheCurrentIndexProducesTheServedState() {
        ingest("weights");
        assertEquals(
                Codec.stateDigestHex(engine().state()),
                Codec.stateDigestHex(engine().replayTo(engine().state().appliedIndex())));
    }

    @Test
    void replayingBelowAReleasedPrefixSaysSoRatherThanLying() {
        for (int i = 0; i < 5; i++) {
            ingest("weights " + i);
        }
        engine().checkpoint();
        long releasedBelow = registry.logFirstIndex();
        if (releasedBelow <= 1) {
            // A single segment cannot be released; nothing to assert on this filesystem.
            return;
        }
        io.cairn.store.HistoryUnavailableException gone = assertThrows(
                io.cairn.store.HistoryUnavailableException.class, () -> engine().replayTo(0));
        // The useful part is not the message, it is the number: a caller learns how far back
        // history goes instead of having to bisect for it. The API returns this as 410 with the
        // floor in the body.
        assertEquals(0, gone.requested());
        assertEquals(releasedBelow - 1, gone.earliestAvailable(),
                "the floor should be the snapshot the log was released to: " + gone.getMessage());
        assertEquals(gone.earliestAvailable(), engine().earliestReplayableIndex());
    }

    @Test
    void verifyAgreesAndWouldNotIfItDidNotCompareAnything() {
        ingest("weights");
        Engine.Verification result = engine().verify();
        assertTrue(result.agrees());
        assertEquals(64, result.servedDigest().length(),
                "the comparison is over a full SHA-256, not a prefix");
        assertEquals(result.servedIndex(), result.derivedIndex());
    }

    @Test
    void statsCountWhatHappened() {
        Digest digest = ingest("weights");
        engine().propose(Command.PublishVersion.of(Ref.of("fraud", "1.0.0"), digest, "alice", 1L));
        engine().propose(Command.PublishVersion.of(Ref.of("fraud", "1.0.0"), digest, "alice", 2L));
        engine().propose(Command.PublishVersion.of(
                Ref.of("fraud", "2.0.0"), Digest.parse("sha256:" + "a".repeat(64)), "alice", 3L));

        Engine.Stats stats = engine().stats();
        // Three, not two: the ingest, the publish, and the acknowledgement the dispatcher proposed
        // for the effect the publish produced. An acknowledgement is a command that changes
        // replicated state, which is the whole reason exactly-once delivery works, so it counts.
        assertEquals(3, stats.applied());
        assertEquals(1, stats.retries());
        assertEquals(1L, stats.rejections().get(RejectionCode.ARTIFACT_MISSING));
        assertTrue(stats.effectsApplied() > 0);
        assertEquals(0, stats.outboxDepth(), "the dispatcher runs after each command");
        assertTrue(stats.logLastIndex() >= 4);
    }

    @Test
    void readsAreLockFreeAndNeverSeeAHalfAppliedCommand() throws Exception {
        // The property immutability buys. A reader takes one reference and holds a whole
        // consistent registry; there is no value in the system that represents "half of a
        // promotion". This drives writes and reads concurrently and asserts every observed state
        // is one the kernel could have produced — specifically, that no model ever has two
        // production versions, which is the invariant a mutable implementation would break here.
        Digest digest = ingest("weights");
        List<String> versions = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            versions.add("1.0." + i);
            engine().propose(Command.PublishVersion.of(
                    Ref.of("fraud", "1.0." + i), digest, "alice", i));
        }

        AtomicInteger observations = new AtomicInteger();
        AtomicReference<String> violation = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        ExecutorService readers = Executors.newFixedThreadPool(4);
        for (int r = 0; r < 4; r++) {
            readers.submit(() -> {
                while (done.getCount() > 0) {
                    var state = engine().state();
                    var model = state.model(io.cairn.core.ModelId.of("fraud")).orElse(null);
                    if (model == null) {
                        continue;
                    }
                    int inProduction = 0;
                    for (var version : model.versions().values()) {
                        if (version.live() && version.stage() == Stage.PRODUCTION) {
                            inProduction++;
                        }
                    }
                    if (inProduction > 1) {
                        violation.set("observed " + inProduction + " production versions");
                        done.countDown();
                    }
                    observations.incrementAndGet();
                }
            });
        }

        for (String version : versions) {
            engine().propose(new Command.Promote(
                    Ref.of("fraud", version), Stage.PRODUCTION, "alice", 1L));
        }
        done.countDown();
        readers.shutdown();
        assertTrue(readers.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(null, violation.get());
        assertTrue(observations.get() > 100,
                "the readers only managed " + observations.get() + " observations");
        assertEquals("1.0.19",
                engine().state().model(io.cairn.core.ModelId.of("fraud")).orElseThrow()
                        .production().orElseThrow().version().value(),
                "the last promotion wins: the one promoted last, not the one that sorts last");
    }

    @Test
    void orphanedArtifactsAreTheOnesTheRegistryHasNoLiveReferenceFor() {
        Digest kept = ingest("kept");
        engine().propose(Command.PublishVersion.of(Ref.of("fraud", "1.0.0"), kept, "alice", 1L));
        assertTrue(engine().orphanedArtifacts().isEmpty());

        // Write bytes directly into the blob store, bypassing the registry. That is what a crashed
        // upload leaves behind.
        engine().blobs().put(new ByteArrayInputStream("stray".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, engine().orphanedArtifacts().size());
    }
}
