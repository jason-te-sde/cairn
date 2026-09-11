package io.cairn.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cairn.core.Digest;
import io.cairn.store.memory.InMemoryBlobStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Both blob stores, against the same suite.
 *
 * <p>Every test here runs against the filesystem implementation and the in-memory one, because the
 * port only means something if the two behave the same. A test suite that runs against one of them
 * is a test suite for one of them.
 */
class BlobStoreTest {

    @TempDir
    static Path shared;

    static Stream<Arguments> stores() throws IOException {
        Path fileRoot = Files.createTempDirectory(shared, "blobs-");
        return Stream.of(
                Arguments.of("file", (Supplier<BlobStore>) () -> new FileBlobStore(fileRoot)),
                Arguments.of("memory", (Supplier<BlobStore>) InMemoryBlobStore::new));
    }

    private static InputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static Digest digestOf(String content) {
        return InMemoryBlobStore.digestOf(content.getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void anArtifactIsNamedByWhatItHashesTo(String name, Supplier<BlobStore> factory)
            throws IOException {
        try (BlobStore store = factory.get()) {
            BlobStore.Ingested ingested = store.put(bytes("the weights"));

            assertEquals(digestOf("the weights"), ingested.digest());
            assertEquals(11, ingested.size());
            assertTrue(store.contains(ingested.digest()));
            assertEquals(11, store.size(ingested.digest()));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void aPromisedDigestIsCheckedRatherThanBelieved(String name, Supplier<BlobStore> factory)
            throws IOException {
        try (BlobStore store = factory.get()) {
            Digest lie = digestOf("something else entirely");

            DigestMismatchException refused = assertThrows(DigestMismatchException.class,
                    () -> store.putVerified(lie, bytes("the weights")));

            assertEquals(lie, refused.expected());
            assertEquals(digestOf("the weights"), refused.actual());
            // And nothing was stored under either name. This is the whole point: the registry this
            // project is derived from took a fileHash from its client and never hashed the file.
            assertFalse(store.contains(lie), "the claimed digest must not resolve to anything");
            assertFalse(store.contains(digestOf("the weights")),
                    "and a rejected upload must not be quietly stored under its real digest");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void aCorrectPromiseIsAccepted(String name, Supplier<BlobStore> factory) throws IOException {
        try (BlobStore store = factory.get()) {
            BlobStore.Ingested ingested =
                    store.putVerified(digestOf("the weights"), bytes("the weights"));
            assertEquals(digestOf("the weights"), ingested.digest());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void writingTheSameBytesTwiceIsOneArtifact(String name, Supplier<BlobStore> factory)
            throws IOException {
        try (BlobStore store = factory.get()) {
            BlobStore.Ingested first = store.put(bytes("identical weights"));
            int before = store.list().size();
            BlobStore.Ingested second = store.put(bytes("identical weights"));

            assertEquals(first.digest(), second.digest());
            assertEquals(first.size(), second.size());
            assertEquals(before, store.list().size(), "deduplicated, not stored twice");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void anArtifactReadsBackByteForByte(String name, Supplier<BlobStore> factory)
            throws IOException {
        try (BlobStore store = factory.get()) {
            byte[] content = new byte[256 * 1024];
            new Random(20260911L).nextBytes(content);

            BlobStore.Ingested ingested = store.put(new ByteArrayInputStream(content));
            try (InputStream back = store.open(ingested.digest())) {
                assertEquals(-1, java.util.Arrays.mismatch(content, back.readAllBytes()),
                        "the artifact came back different");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void anEmptyArtifactIsStillAnArtifact(String name, Supplier<BlobStore> factory)
            throws IOException {
        try (BlobStore store = factory.get()) {
            BlobStore.Ingested ingested = store.put(bytes(""));
            assertEquals(0, ingested.size());
            assertTrue(store.contains(ingested.digest()));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void anAbsentArtifactIsAnAnswerNotACrash(String name, Supplier<BlobStore> factory)
            throws IOException {
        try (BlobStore store = factory.get()) {
            Digest missing = digestOf("never uploaded");
            assertFalse(store.contains(missing));
            assertThrows(NoSuchBlobException.class, () -> store.open(missing));
            assertThrows(NoSuchBlobException.class, () -> store.size(missing));
            assertFalse(store.delete(missing));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void deletingRemovesExactlyOneArtifact(String name, Supplier<BlobStore> factory)
            throws IOException {
        try (BlobStore store = factory.get()) {
            Digest kept = store.put(bytes("keep " + name)).digest();
            Digest going = store.put(bytes("delete " + name)).digest();

            assertTrue(store.delete(going));
            assertFalse(store.contains(going));
            assertTrue(store.contains(kept));
            assertFalse(store.delete(going), "a second delete reports that there was nothing");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stores")
    void theListingIsSortedAndTotalsAddUp(String name, Supplier<BlobStore> factory)
            throws IOException {
        try (BlobStore store = factory.get()) {
            for (int i = 0; i < 8; i++) {
                store.put(bytes("artifact " + name + " " + i));
            }
            List<Digest> listing = store.list();
            assertTrue(listing.size() >= 8);

            List<Digest> sorted = listing.stream().sorted().toList();
            assertEquals(sorted, listing, "the listing must be ordered so that fsck output is");

            long summed = 0;
            for (Digest digest : listing) {
                summed += store.size(digest);
            }
            assertEquals(summed, store.totalBytes());
        }
    }
}
