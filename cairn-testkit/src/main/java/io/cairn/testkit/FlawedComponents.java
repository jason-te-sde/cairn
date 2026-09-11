package io.cairn.testkit;

import io.cairn.core.Digest;
import io.cairn.core.Registry;
import io.cairn.core.SequencedEffect;
import io.cairn.effects.AppliedLedger;
import io.cairn.effects.EffectSink;
import io.cairn.store.BlobStore;
import io.cairn.store.SnapshotStore;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Broken versions of the components around the kernel, for proving the suite notices.
 *
 * <p>These are not mocks. Each one is a faithful reproduction of a specific defect — three of them
 * defects the system this project is derived from actually shipped — wired into the real simulator
 * alongside the real kernel, so the failure it produces is the failure a deployment would have
 * produced. {@code FlawTest} asserts which check catches which, and {@link Flaw} records the
 * mapping.
 */
public final class FlawedComponents {

    private FlawedComponents() {}

    /**
     * {@link Flaw#SNAPSHOT_ALIASES_LIVE_STATE}: a restore that makes later writes land inside the
     * snapshot.
     *
     * <p>The inherited defect, reproduced by its observable consequence. The original closed its
     * live RocksDB and reopened it on the checkpoint directory, so everything written after a
     * restore went into the snapshot and vanished at the next one. Here: once {@link #load} has
     * been called, the next {@link #save} writes the state that was loaded rather than the state it
     * was given. A second restart then comes back to where the first one did, and every command in
     * between is gone.
     */
    public static SnapshotStore snapshotStoreThatAliasesLiveState(SnapshotStore delegate) {
        return new SnapshotStore() {
            private Registry restored;

            @Override
            public void save(Registry state) {
                delegate.save(restored != null ? restored : state);
            }

            @Override
            public Optional<Registry> load() {
                Optional<Registry> loaded = delegate.load();
                loaded.ifPresent(state -> restored = state);
                return loaded;
            }

            @Override
            public int count() {
                return delegate.count();
            }

            @Override
            public void close() throws java.io.IOException {
                delegate.close();
            }
        };
    }

    /**
     * {@link Flaw#TRUST_CLIENT_DIGEST}: a blob store that files bytes under the digest it was told
     * rather than the one they have.
     *
     * <p>The inherited defect. The original took a {@code fileHash} field from the request and
     * stored it, having never hashed the file, so its integrity guarantee was that nobody had lied
     * to it. This one implements {@code putVerified} by ignoring the verification: the bytes go in
     * under {@code expected}, whatever they actually are.
     */
    public static BlobStore blobStoreThatTrustsTheClient(BlobStore delegate) {
        return new BlobStore() {
            /**
             * Bytes filed under the name the caller asked for.
             *
             * <p>Kept here rather than handed to the delegate, because the delegate computes the
             * digest from the bytes and would file them correctly. Trusting the client means the
             * client's digest becomes the key, and that is the whole defect: a later resolution of
             * that digest returns bytes that are not it.
             */
            private final java.util.TreeMap<Digest, byte[]> trusted = new java.util.TreeMap<>();

            @Override
            public Ingested put(InputStream bytes) {
                return delegate.put(bytes);
            }

            @Override
            public Ingested putVerified(Digest expected, InputStream bytes) {
                // No comparison, and no hashing. The digest the caller promised becomes the digest
                // of record, whatever the bytes turn out to be.
                try {
                    byte[] content = bytes.readAllBytes();
                    trusted.put(expected, content);
                    return new Ingested(expected, content.length);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }

            @Override
            public InputStream open(Digest digest) {
                byte[] content = trusted.get(digest);
                return content == null
                        ? delegate.open(digest)
                        : new java.io.ByteArrayInputStream(content);
            }

            @Override
            public boolean contains(Digest digest) {
                return trusted.containsKey(digest) || delegate.contains(digest);
            }

            @Override
            public long size(Digest digest) {
                byte[] content = trusted.get(digest);
                return content == null ? delegate.size(digest) : content.length;
            }

            @Override
            public boolean delete(Digest digest) {
                boolean removed = trusted.remove(digest) != null;
                return delegate.delete(digest) || removed;
            }

            @Override
            public List<Digest> list() {
                java.util.TreeSet<Digest> all = new java.util.TreeSet<>(trusted.keySet());
                all.addAll(delegate.list());
                return List.copyOf(all);
            }

            @Override
            public long totalBytes() {
                long total = delegate.totalBytes();
                for (byte[] content : trusted.values()) {
                    total += content.length;
                }
                return total;
            }

            @Override
            public void close() throws java.io.IOException {
                delegate.close();
            }
        };
    }

    /**
     * {@link Flaw#WATERMARK_BEFORE_DELIVERY}: a consumer that records an effect as applied before
     * applying it.
     *
     * <p>The ordering mistake, and it fails in the wrong direction: a crash or a refusal between
     * the two loses the effect permanently and silently, where the correct order loses nothing and
     * occasionally repeats. An artifact that is never collected and a cache that serves a retired
     * model are both this bug.
     */
    public static EffectSink sinkThatRecordsBeforeDelivering(
            EffectSink delegate, AppliedLedger ledger) {
        return new EffectSink() {
            @Override
            public void deliver(SequencedEffect effect) {
                if (effect.seq() <= ledger.applied()) {
                    return;
                }
                ledger.record(effect.seq());
                delegate.deliver(effect);
            }

            @Override
            public String name() {
                return "watermark-first(" + delegate.name() + ")";
            }
        };
    }
}
