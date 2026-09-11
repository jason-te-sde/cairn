package io.cairn.store.memory;

import io.cairn.core.Digest;
import io.cairn.store.BlobStore;
import io.cairn.store.DigestMismatchException;
import io.cairn.store.NoSuchBlobException;
import io.cairn.store.StoreException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Artifacts in a map, with the same refusal to believe a digest it was handed.
 *
 * <p>Used by the simulator and by anybody embedding the kernel in a test. It hashes what it is
 * given rather than trusting a parameter, which means a test written against this store and a
 * deployment running against the file store fail the same way on a corrupt upload — the point of
 * having the port at all.
 */
public final class InMemoryBlobStore implements BlobStore {

    private final TreeMap<Digest, byte[]> artifacts = new TreeMap<>();

    @Override
    public Ingested put(InputStream bytes) {
        return write(bytes, null);
    }

    @Override
    public Ingested putVerified(Digest expected, InputStream bytes) {
        return write(bytes, expected);
    }

    private Ingested write(InputStream bytes, Digest expected) {
        byte[] content;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            bytes.transferTo(buffer);
            content = buffer.toByteArray();
        } catch (IOException e) {
            throw new StoreException("cannot read the artifact", e);
        }
        Digest computed = digestOf(content);
        if (expected != null && !expected.equals(computed)) {
            throw new DigestMismatchException(expected, computed);
        }
        artifacts.put(computed, content);
        return new Ingested(computed, content.length);
    }

    @Override
    public InputStream open(Digest digest) {
        byte[] content = artifacts.get(digest);
        if (content == null) {
            throw new NoSuchBlobException(digest);
        }
        return new ByteArrayInputStream(content);
    }

    @Override
    public boolean contains(Digest digest) {
        return artifacts.containsKey(digest);
    }

    @Override
    public long size(Digest digest) {
        byte[] content = artifacts.get(digest);
        if (content == null) {
            throw new NoSuchBlobException(digest);
        }
        return content.length;
    }

    @Override
    public boolean delete(Digest digest) {
        return artifacts.remove(digest) != null;
    }

    @Override
    public List<Digest> list() {
        return new ArrayList<>(artifacts.keySet());
    }

    @Override
    public long totalBytes() {
        long total = 0;
        for (byte[] content : artifacts.values()) {
            total += content.length;
        }
        return total;
    }

    @Override
    public void close() {
        // Nothing to release.
    }

    /** SHA-256 of some bytes, for a caller that wants the digest without storing them. */
    public static Digest digestOf(byte[] content) {
        try {
            return Digest.ofSha256(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
