package io.cairn.server;

import io.cairn.store.Durability;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * How the server was told to run: a properties file, with flags overriding it.
 *
 * <p>The one rule worth reading is {@link #validate}. A node refuses to bind a non-loopback address
 * without a client token unless {@code --insecure} is passed. That is deliberate: a laptop stays one
 * command, and exposing an unauthenticated model registry — which is to say, write access to
 * whatever your serving tier loads — becomes something you have to mean.
 *
 * @param dataDir where the log, snapshots and artifacts live
 * @param address the address to bind
 * @param port the port to bind, 0 for an ephemeral one
 * @param token the bearer token for reads and publishes, or null
 * @param adminToken the bearer token for stage changes and deletions, or null
 * @param insecure whether to allow a non-loopback bind without a token
 * @param durability when the log reaches the disk
 * @param snapshotEvery how many commands between snapshots, 0 to never snapshot
 * @param effectsLog a file to append delivered effects to as JSON lines, or null
 * @param dispatchEveryMillis how often to retry a stalled dispatcher
 */
public record ServerConfig(
        Path dataDir,
        String address,
        int port,
        String token,
        String adminToken,
        boolean insecure,
        Durability durability,
        int snapshotEvery,
        Path effectsLog,
        long dispatchEveryMillis) {

    /** Defaults: a loopback bind on 9080, fsync per command, a snapshot every 1000 commands. */
    public static ServerConfig defaults(Path dataDir) {
        return new ServerConfig(
                dataDir, "127.0.0.1", 9080, null, null, false,
                Durability.SYNC_EACH, 1000, null, 2000);
    }

    /**
     * Parses arguments, reading {@code --config=<file>} first so flags override it.
     *
     * @throws IllegalArgumentException on an unknown flag, rather than ignoring it: a typo in a
     *     flag that silently does nothing is how a node ends up running without the setting
     *     somebody thought they had applied
     */
    public static ServerConfig parse(String[] args) {
        Properties properties = new Properties();
        for (String arg : args) {
            if (arg.startsWith("--config=")) {
                Path file = Path.of(arg.substring("--config=".length()));
                try (var in = Files.newInputStream(file)) {
                    properties.load(in);
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot read " + file, e);
                }
            }
        }
        for (String arg : args) {
            if (arg.equals("--insecure")) {
                properties.setProperty("insecure", "true");
                continue;
            }
            if (arg.startsWith("--config=")) {
                continue;
            }
            if (!arg.startsWith("--") || !arg.contains("=")) {
                throw new IllegalArgumentException("not a flag: " + arg);
            }
            int equals = arg.indexOf('=');
            String name = arg.substring(2, equals);
            if (!KNOWN.contains(name)) {
                throw new IllegalArgumentException(
                        "unknown flag --" + name + "; known flags are " + KNOWN);
            }
            properties.setProperty(name, arg.substring(equals + 1));
        }
        return from(properties);
    }

    private static final List<String> KNOWN = List.of(
            "data-dir", "address", "port", "token", "admin-token", "insecure",
            "durability", "snapshot-every", "effects-log", "dispatch-every-millis");

    private static ServerConfig from(Properties properties) {
        return new ServerConfig(
                Path.of(properties.getProperty("data-dir", "cairn-data")),
                properties.getProperty("address", "127.0.0.1"),
                Integer.parseInt(properties.getProperty("port", "9080")),
                emptyToNull(properties.getProperty("token")),
                emptyToNull(properties.getProperty("admin-token")),
                Boolean.parseBoolean(properties.getProperty("insecure", "false")),
                Durability.valueOf(
                        properties.getProperty("durability", "SYNC_EACH").toUpperCase()),
                Integer.parseInt(properties.getProperty("snapshot-every", "1000")),
                properties.getProperty("effects-log") == null
                        ? null
                        : Path.of(properties.getProperty("effects-log")),
                Long.parseLong(properties.getProperty("dispatch-every-millis", "2000")));
    }

    /**
     * Refuses a configuration that would expose an unauthenticated registry.
     *
     * @throws IllegalStateException with an explanation and the flag that overrides it
     */
    public void validate() {
        boolean loopback = address.equals("127.0.0.1")
                || address.equals("::1")
                || address.equals("localhost");
        if (!loopback && token == null && !insecure) {
            throw new IllegalStateException(
                    "refusing to bind " + address + " without --token. A model registry decides"
                            + " what your serving tier loads, so an unauthenticated one is a"
                            + " remote code execution primitive. Pass --token=<secret>, or"
                            + " --insecure if you have another control in front of it.");
        }
        if (adminToken != null && adminToken.equals(token)) {
            throw new IllegalStateException(
                    "--admin-token must differ from --token; ejecting a version is not the same"
                            + " privilege as publishing one");
        }
        if (snapshotEvery < 0) {
            throw new IllegalStateException("--snapshot-every must not be negative");
        }
    }

    /** The flags, for {@code --help}. */
    public static List<String> help() {
        List<String> lines = new ArrayList<>();
        lines.add("  --config=<file>             properties file; flags below override it");
        lines.add("  --data-dir=<path>           log, snapshots and artifacts (cairn-data)");
        lines.add("  --address=<host>            address to bind (127.0.0.1)");
        lines.add("  --port=<port>               port to bind, 0 for ephemeral (9080)");
        lines.add("  --token=<secret>            bearer token for reads and publishes");
        lines.add("  --admin-token=<secret>      bearer token for stage changes and deletions");
        lines.add("  --insecure                  allow a non-loopback bind with no token");
        lines.add("  --durability=<mode>         SYNC_EACH | SYNC_ON_DEMAND | NONE (SYNC_EACH)");
        lines.add("  --snapshot-every=<n>        commands between snapshots, 0 to disable (1000)");
        lines.add("  --effects-log=<file>        append delivered effects as JSON lines");
        lines.add("  --dispatch-every-millis=<n> retry interval for a stalled dispatcher (2000)");
        return lines;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
