package io.cairn.server;

import com.sun.net.httpserver.HttpServer;
import io.cairn.store.BlobStore;
import io.cairn.store.CommandLog;
import io.cairn.store.FileBlobStore;
import io.cairn.store.FileCommandLog;
import io.cairn.store.FileSnapshotStore;
import io.cairn.store.SnapshotStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code cairnd}: the registry as one process.
 *
 * <p>No framework and no container. The JDK's own HTTP server, a fixed thread pool for requests,
 * and one thread that owns the kernel. That is the whole runtime, which is why the dependency list
 * in the README is one entry long and why a clean clone builds in seconds.
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    /** How many requests can be in flight. Uploads are I/O-bound, so this is generous. */
    private static final int REQUEST_THREADS = 16;

    private Main() {}

    /**
     * Dispatches: a flag-only invocation starts the server, anything else is a client command.
     *
     * <p>One jar, two programs. {@code scripts/cairnd} and {@code scripts/cairnctl} are one-line
     * wrappers, which keeps the container image to a single artifact and means the client is always
     * the same version as the server it was built with.
     */
    public static void main(String[] args) throws IOException {
        if (args.length > 0 && !args[0].startsWith("-")) {
            Cli.main(args);
            return;
        }
        serve(args);
    }

    private static void serve(String[] args) throws IOException {
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                System.out.println("cairnd — a deterministic model registry");
                System.out.println();
                System.out.println("  cairnd [flags]           start the server");
                System.out.println("  cairnd <command> [args]  run a client command"
                        + " (see `cairnd help`)");
                System.out.println();
                ServerConfig.help().forEach(System.out::println);
                return;
            }
        }

        ServerConfig config;
        try {
            config = ServerConfig.parse(args);
            config.validate();
        } catch (RuntimeException e) {
            System.err.println("cairnd: " + e.getMessage());
            System.err.println();
            ServerConfig.help().forEach(System.err::println);
            System.exit(2);
            return;
        }

        Path data = config.dataDir();
        CommandLog log = new FileCommandLog(data.resolve("log"), config.durability());
        SnapshotStore snapshots = new FileSnapshotStore(data.resolve("snapshots"));
        BlobStore blobs = new FileBlobStore(data.resolve("artifacts"));
        Engine engine = new Engine(
                config, log, snapshots, blobs,
                new ServerSink(blobs, config.effectsLog()));

        HttpServer http = HttpServer.create(
                new InetSocketAddress(config.address(), config.port()), 64);
        http.createContext("/", new Api(engine, config));
        http.setExecutor(Executors.newFixedThreadPool(REQUEST_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "cairn-http");
            thread.setDaemon(true);
            return thread;
        }));
        http.start();

        int port = http.getAddress().getPort();
        LOG.info("cairnd listening on {}:{} (data {}, durability {}, auth {})",
                config.address(), port, data.toAbsolutePath(), config.durability(),
                config.token() == null ? "off" : "bearer token");
        if (config.token() == null) {
            LOG.warn("no --token: every request is authorized. Fine on a loopback bind, not"
                    + " otherwise.");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("stopping");
            // Two seconds for in-flight requests, then the engine drains the outbox and closes the
            // log. A shutdown that skipped the drain would leave the outside world waiting for
            // effects the next start has to rediscover, which is correct but slow.
            http.stop(2);
            try {
                engine.close();
            } catch (IOException e) {
                LOG.warn("could not close the engine cleanly: {}", e.getMessage());
            }
        }, "cairn-shutdown"));
    }
}
