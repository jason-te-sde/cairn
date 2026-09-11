package io.cairn.server;

import com.sun.net.httpserver.HttpServer;
import io.cairn.store.BlobStore;
import io.cairn.store.CommandLog;
import io.cairn.store.Durability;
import io.cairn.store.FileBlobStore;
import io.cairn.store.FileCommandLog;
import io.cairn.store.FileSnapshotStore;
import io.cairn.store.SnapshotStore;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * A real engine behind a real HTTP server on an ephemeral port.
 *
 * <p>Nothing is mocked, including the filesystem. The integration tests are the layer where a codec
 * bug or a file-handle leak shows up, and both of those are invisible to anything in-process.
 */
final class TestRegistry implements Closeable {

    private final HttpServer http;
    private final Engine engine;
    private final String base;

    TestRegistry(Path dataDir) throws IOException {
        this(dataDir, null, null);
    }

    TestRegistry(Path dataDir, String token, String adminToken) throws IOException {
        this(dataDir, token, adminToken, io.cairn.core.Limits.MAX_ARTIFACT_BYTES);
    }

    TestRegistry(Path dataDir, String token, String adminToken, long maxArtifactBytes)
            throws IOException {
        ServerConfig config = new ServerConfig(
                dataDir, "127.0.0.1", 0, token, adminToken, false,
                Durability.SYNC_EACH, 0, null, 60_000, maxArtifactBytes);
        CommandLog log = new FileCommandLog(dataDir.resolve("log"), config.durability());
        SnapshotStore snapshots = new FileSnapshotStore(dataDir.resolve("snapshots"));
        BlobStore blobs = new FileBlobStore(dataDir.resolve("artifacts"), maxArtifactBytes);
        this.engine = new Engine(
                config, log, snapshots, blobs, new ServerSink(blobs, null));

        this.http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        http.createContext("/", new Api(engine, config));
        http.setExecutor(Executors.newFixedThreadPool(4));
        http.start();
        this.base = "http://127.0.0.1:" + http.getAddress().getPort();
    }

    String base() {
        return base;
    }

    Engine engine() {
        return engine;
    }

    /**
     * Deliberately does not expose the log.
     *
     * <p>It used to, and a test then called {@code log().firstIndex()} from the test thread while
     * the engine's own thread owned it — the same mistake the production code was making, in the
     * harness. Anything a test wants to know about the log it can ask the engine for, which is the
     * only thing entitled to ask.
     */
    long logFirstIndex() {
        return engine.stats().logFirstIndex();
    }

    @Override
    public void close() throws IOException {
        http.stop(0);
        engine.close();
    }
}
