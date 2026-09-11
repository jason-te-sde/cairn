package io.cairn.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cairn.core.Digest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The parts of the filesystem blob store that are about the filesystem. */
class FileBlobStoreTest {

    @TempDir
    Path root;

    private static InputStream bytes(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theLayoutShardsByTheFirstFourHexCharacters() throws IOException {
        try (FileBlobStore store = new FileBlobStore(root)) {
            Digest digest = store.put(bytes("the weights")).digest();
            String hex = digest.hex();

            Path expected = root.resolve(hex.substring(0, 2))
                    .resolve(hex.substring(2, 4))
                    .resolve(hex);
            assertTrue(Files.isRegularFile(expected),
                    "expected the artifact at " + expected);
            assertEquals(11, Files.size(expected));
        }
    }

    @Test
    void aRejectedUploadLeavesNothingBehind() throws IOException {
        try (FileBlobStore store = new FileBlobStore(root)) {
            Digest lie = new Digest(Digest.SHA256, "f".repeat(64));
            assertThrows(DigestMismatchException.class,
                    () -> store.putVerified(lie, bytes("the weights")));

            try (var listing = Files.walk(root)) {
                List<Path> files = listing.filter(Files::isRegularFile).toList();
                assertTrue(files.isEmpty(),
                        "a rejected upload left files behind: " + files);
            }
        }
    }

    @Test
    void anIncompleteUploadIsClearedAtStartup() throws IOException {
        Path temporary = root.resolve("tmp");
        Files.createDirectories(temporary);
        Path leftover = Files.createFile(temporary.resolve("upload-crashed.part"));
        Files.writeString(leftover, "half an artifact");

        try (FileBlobStore store = new FileBlobStore(root)) {
            assertFalse(Files.exists(leftover),
                    "a partial upload from a previous life should be cleared");
            assertTrue(store.list().isEmpty(),
                    "and it should never have been visible as an artifact");
        }
    }

    @Test
    void anOversizedArtifactIsRefusedWhileStreamingRatherThanAfterwards() throws IOException {
        try (FileBlobStore store = new FileBlobStore(root, 1024)) {
            byte[] tooBig = new byte[4096];
            ArtifactTooLargeException refused = assertThrows(ArtifactTooLargeException.class,
                    () -> store.put(new ByteArrayInputStream(tooBig)));
            assertEquals(1024, refused.limit());
            assertTrue(refused.written() > 1024, refused.getMessage());

            try (var listing = Files.walk(root)) {
                assertTrue(listing.filter(Files::isRegularFile).findAny().isEmpty(),
                        "the partial write must not survive");
            }
        }
    }

    @Test
    void anArtifactSurvivesAReopen() throws IOException {
        Digest digest;
        try (FileBlobStore store = new FileBlobStore(root)) {
            digest = store.put(bytes("durable weights")).digest();
        }
        try (FileBlobStore reopened = new FileBlobStore(root)) {
            assertTrue(reopened.contains(digest));
            try (InputStream back = reopened.open(digest)) {
                assertEquals("durable weights",
                        new String(back.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void aStrayFileIsReportedRatherThanTreatedAsAnArtifact() throws IOException {
        try (FileBlobStore store = new FileBlobStore(root)) {
            Digest real = store.put(bytes("real weights")).digest();
            Path shard = root.resolve(real.hex().substring(0, 2));
            Files.writeString(shard.resolve("README"), "not an artifact");

            // Listing is what fsck uses to find orphans, so a file that is not a digest must not
            // appear as one: reporting it as an artifact would invite deleting it.
            assertEquals(1, store.list().size());
        }
    }
}
