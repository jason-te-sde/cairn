package io.cairn.store;

import io.cairn.core.Digest;
import io.cairn.core.Limits;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Artifacts on a filesystem, at paths derived from what they hash to.
 *
 * <pre>
 * blobs/ab/cd/abcd...ef        the artifact, named by its full digest
 * blobs/tmp/&lt;random&gt;           being written; hashed on the way past, moved when it is whole
 * </pre>
 *
 * <p>Two levels of two hex characters, because a single directory with a hundred thousand entries
 * is slow to list on most filesystems and unpleasant on all of them. The full digest is still the
 * file name, so a path is unambiguous on its own and an operator reading a stack trace does not
 * have to reassemble one.
 *
 * <p>The write path is the part worth reading. Bytes go to a temporary file through a
 * {@link DigestOutputStream}, so the digest is a measurement rather than a claim; the size limit is
 * enforced while streaming rather than from a header a client sent; and the move into place is
 * atomic, so a crash mid-upload leaves a temporary file and never a truncated artifact under a
 * digest that promises different content. If the target already exists the temporary file is simply
 * dropped: two uploads of the same bytes are one artifact, which is what content addressing is for.
 */
public final class FileBlobStore implements BlobStore {

    private static final Logger LOG = LoggerFactory.getLogger(FileBlobStore.class);

    private final Path root;
    private final Path temporary;
    private final long maxBytes;

    public FileBlobStore(Path root) {
        this(root, Limits.MAX_ARTIFACT_BYTES);
    }

    /**
     * Opens a blob store that refuses anything over {@code maxBytes}.
     *
     * <p>Public because a deployment needs to be able to lower it. The default is 256 GiB, which is
     * a ceiling rather than a policy: a registry exposed to anything needs a number somebody chose.
     */
    public FileBlobStore(Path root, long maxBytes) {
        this.root = root;
        this.temporary = root.resolve("tmp");
        this.maxBytes = maxBytes;
        try {
            Files.createDirectories(temporary);
            cleanTemporaries();
        } catch (IOException e) {
            throw new StoreException("cannot open the blob store at " + root, e);
        }
    }

    @Override
    public Ingested put(InputStream bytes) {
        return write(bytes, null);
    }

    @Override
    public Ingested putVerified(Digest expected, InputStream bytes) {
        if (expected == null) {
            throw new IllegalArgumentException("expected digest must not be null");
        }
        return write(bytes, expected);
    }

    private Ingested write(InputStream bytes, Digest expected) {
        Path scratch;
        try {
            scratch = Files.createTempFile(temporary, "upload-", ".part");
        } catch (IOException e) {
            throw new StoreException("cannot create a temporary file in " + temporary, e);
        }

        long written = 0;
        Digest computed;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (OutputStream file = Files.newOutputStream(scratch);
                    DigestOutputStream hashing = new DigestOutputStream(file, sha)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = bytes.read(buffer)) >= 0) {
                    written += read;
                    if (written > maxBytes) {
                        // Enforced here rather than from a Content-Length, because a header is
                        // something the client chose and this is something that happened. Its own
                        // exception type, so the HTTP layer can answer 413 with the ceiling rather
                        // than 409 with prose.
                        throw new ArtifactTooLargeException(written, maxBytes);
                    }
                    hashing.write(buffer, 0, read);
                }
            }
            computed = Digest.ofSha256(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            deleteQuietly(scratch);
            throw new IllegalStateException("SHA-256 is unavailable", e);
        } catch (IOException | StoreException e) {
            deleteQuietly(scratch);
            throw e instanceof StoreException stored
                    ? stored
                    : new StoreException("cannot write an artifact", e);
        }

        if (expected != null && !expected.equals(computed)) {
            // Nothing is moved into place and nothing is recorded. The registry never learns that
            // this upload happened, which is the only safe outcome: a digest is a name, and
            // storing these bytes under the requested one would make the name a lie for everybody
            // who ever resolves it.
            deleteQuietly(scratch);
            throw new DigestMismatchException(expected, computed);
        }

        Path target = pathFor(computed);
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                deleteQuietly(scratch);
                return new Ingested(computed, Files.size(target));
            }
            Files.move(scratch, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteQuietly(scratch);
            throw new StoreException("cannot store artifact " + computed.shortHex(), e);
        }
        LOG.debug("stored artifact {} ({} bytes)", computed.shortHex(), written);
        return new Ingested(computed, written);
    }

    @Override
    public InputStream open(Digest digest) {
        Path file = pathFor(digest);
        if (!Files.isRegularFile(file)) {
            throw new NoSuchBlobException(digest);
        }
        try {
            return Files.newInputStream(file);
        } catch (IOException e) {
            throw new StoreException("cannot read artifact " + digest.shortHex(), e);
        }
    }

    @Override
    public boolean contains(Digest digest) {
        return Files.isRegularFile(pathFor(digest));
    }

    @Override
    public long size(Digest digest) {
        try {
            return Files.size(pathFor(digest));
        } catch (IOException e) {
            throw new NoSuchBlobException(digest);
        }
    }

    @Override
    public boolean delete(Digest digest) {
        try {
            boolean removed = Files.deleteIfExists(pathFor(digest));
            if (removed) {
                LOG.info("collected artifact {}", digest.shortHex());
            }
            return removed;
        } catch (IOException e) {
            throw new StoreException("cannot delete artifact " + digest.shortHex(), e);
        }
    }

    @Override
    public List<Digest> list() {
        List<Digest> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(temporary))
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        if (name.length() == Digest.SHA256_HEX_LENGTH) {
                            found.add(new Digest(Digest.SHA256, name));
                        } else {
                            LOG.warn("ignoring unexpected file in the blob store: {}", path);
                        }
                    });
        } catch (IOException e) {
            throw new StoreException("cannot list the blob store at " + root, e);
        }
        found.sort(Digest::compareTo);
        return found;
    }

    @Override
    public long totalBytes() {
        long total = 0;
        for (Digest digest : list()) {
            total += size(digest);
        }
        return total;
    }

    @Override
    public void close() {
        // Nothing held open.
    }

    /**
     * Removes leftover partial uploads.
     *
     * <p>Run at startup. A crash mid-upload leaves a file under {@code tmp/} that nothing will ever
     * look for, and it is safe to remove precisely because an artifact is only ever addressable
     * once it has been moved out of there.
     */
    private void cleanTemporaries() throws IOException {
        try (Stream<Path> leftovers = Files.list(temporary)) {
            leftovers.forEach(leftover -> {
                deleteQuietly(leftover);
                LOG.info("removed an incomplete upload: {}", leftover.getFileName());
            });
        }
    }

    private Path pathFor(Digest digest) {
        String hex = digest.hex();
        return root.resolve(hex.substring(0, 2)).resolve(hex.substring(2, 4)).resolve(hex);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.warn("could not remove {}: {}", path, e.getMessage());
        }
    }
}
