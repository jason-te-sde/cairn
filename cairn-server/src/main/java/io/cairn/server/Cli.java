package io.cairn.server;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code cairnctl}: the registry from a terminal.
 *
 * <p>Output is columns rather than JSON, because the audience is a person looking at a registry
 * they did not build. {@code --json} prints the response verbatim for anything that needs piping.
 *
 * <p>Exit codes are meant for scripts: 0 on success, 1 on a refusal the registry explained, 2 on
 * bad usage, 3 when the server could not be reached. A tool that exits 1 for all three makes a
 * deployment script unable to tell "the version is already published" from "the registry is down",
 * and those need different responses.
 */
final class Cli {

    private static final int REFUSED = 1;
    private static final int USAGE = 2;
    private static final int UNREACHABLE = 3;

    /**
     * How long to wait for a whole response, not just for the connection.
     *
     * <p>Connect timeouts are the easy half. A registry whose kernel thread is blocked on a stuck
     * disk still accepts connections and then says nothing — which is a symptom
     * {@code docs/operations.md} explicitly lists, and this client used to hang forever on exactly
     * the case somebody would be reaching for it to diagnose. Generous rather than short, because
     * a large upload and a replay over a long log are both legitimately slow.
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String base;
    private final String token;
    private final String adminToken;
    private final boolean json;
    private final Duration timeout;

    private Cli(String base, String token, String adminToken, boolean json, Duration timeout) {
        this.base = base;
        this.token = token;
        this.adminToken = adminToken;
        this.json = json;
        this.timeout = timeout;
    }

    static void main(String[] args) {
        System.exit(execute(args));
    }

    /**
     * Runs a command and returns the exit code instead of calling {@link System#exit}.
     *
     * <p>Split out so the tests can drive the real client against a real server. A client whose
     * only entry point terminates the JVM is a client nobody tests, and the formatting code here is
     * the part a person actually sees.
     */
    static int execute(String[] args) {
        String base = System.getenv().getOrDefault("CAIRN_URL", "http://127.0.0.1:9080");
        String token = System.getenv("CAIRN_TOKEN");
        String adminToken = System.getenv("CAIRN_ADMIN_TOKEN");
        boolean json = false;
        Duration timeout = DEFAULT_TIMEOUT;

        List<String> rest = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--url=")) {
                base = arg.substring("--url=".length());
            } else if (arg.startsWith("--token=")) {
                token = arg.substring("--token=".length());
            } else if (arg.startsWith("--admin-token=")) {
                adminToken = arg.substring("--admin-token=".length());
            } else if (arg.startsWith("--timeout=")) {
                long seconds = Long.parseLong(arg.substring("--timeout=".length()));
                if (seconds <= 0) {
                    throw new IllegalArgumentException("--timeout must be positive: " + seconds);
                }
                timeout = Duration.ofSeconds(seconds);
            } else if (arg.equals("--json")) {
                json = true;
            } else {
                rest.add(arg);
            }
        }
        if (rest.isEmpty() || rest.get(0).equals("help")) {
            usage();
            return rest.isEmpty() ? USAGE : 0;
        }

        Cli cli = new Cli(base, token, adminToken, json, timeout);
        try {
            return cli.run(rest);
        } catch (java.net.http.HttpTimeoutException e) {
            // Distinct from a refused connection, and worth saying so: the registry is up and not
            // answering, which points at the kernel thread rather than at the network.
            System.err.println("cairnctl: " + base + " accepted the request and did not answer"
                    + " within " + cli.timeout.toSeconds() + "s. The registry is running but its"
                    + " kernel thread is not responding — check disk I/O and free space, and see"
                    + " docs/operations.md. Raise the wait with --timeout=<seconds>.");
            return UNREACHABLE;
        } catch (IOException e) {
            System.err.println("cairnctl: cannot reach " + base + ": " + e.getMessage());
            return UNREACHABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return UNREACHABLE;
        } catch (IllegalArgumentException e) {
            System.err.println("cairnctl: " + e.getMessage());
            return USAGE;
        }
    }

    private int run(List<String> args) throws IOException, InterruptedException {
        String command = args.get(0);
        List<String> rest = args.subList(1, args.size());
        return switch (command) {
            case "status" -> status();
            case "put" -> put(require(rest, 1, "put <file>").get(0));
            case "publish" -> publish(rest);
            case "stage" -> stage(require(rest, 2, "stage <model@version> <stage>"));
            case "get" -> get(require(rest, 1, "get <model@version>").get(0));
            case "ls" -> list(rest.isEmpty() ? null : rest.get(0));
            case "production" -> production(require(rest, 1, "production <model>").get(0));
            case "lineage" -> lineage(require(rest, 1, "lineage <model@version>").get(0));
            case "rm" -> remove(require(rest, 1, "rm <model@version>").get(0));
            case "effects" -> effects();
            case "verify" -> verify();
            case "state" -> state(rest);
            case "fsck" -> fsck();
            default -> {
                System.err.println("cairnctl: unknown command '" + command + "'");
                usage();
                yield USAGE;
            }
        };
    }

    // ---- commands ----------------------------------------------------------------------------

    private int status() throws IOException, InterruptedException {
        Response models = send("GET", "/v1/models", null, false);
        if (models.status() != 200) {
            return report(models);
        }
        Response effects = send("GET", "/v1/effects", null, false);
        Response ready = send("GET", "/readyz", null, false);
        if (json) {
            System.out.println(models.body());
            return 0;
        }

        System.out.println(ready.body().trim());
        Map<String, Object> parsed = Json.parseObject(models.body());
        Object list = parsed.get("models");
        if (!(list instanceof List<?> entries) || entries.isEmpty()) {
            System.out.println("no models");
        } else {
            System.out.printf("%-24s %8s %8s  %s%n",
                    "MODEL", "VERSIONS", "LIVE", "PRODUCTION");
            for (Object entry : entries) {
                @SuppressWarnings("unchecked")
                Map<String, Object> model = (Map<String, Object>) entry;
                System.out.printf("%-24s %8s %8s  %s%n",
                        model.get("model"), model.get("versions"), model.get("live"),
                        model.get("production") == null ? "-" : model.get("production"));
            }
        }
        Map<String, Object> outbox = Json.parseObject(effects.body());
        System.out.println();
        System.out.println("effects: " + outbox.get("depth") + " pending, delivered through "
                + outbox.get("dispatched_through"));
        return 0;
    }

    private int put(String file) throws IOException, InterruptedException {
        Path path = Path.of(file);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("not a file: " + file);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/v1/artifacts"))
                .timeout(timeout)
                .PUT(HttpRequest.BodyPublishers.ofFile(path))
                .header("Content-Type", "application/octet-stream");
        authorize(request, false);
        Response response = exchange(request);
        if (response.status() != 201) {
            return report(response);
        }
        if (json) {
            System.out.println(response.body());
        } else {
            Map<String, Object> parsed = Json.parseObject(response.body());
            System.out.println(parsed.get("digest") + "  " + parsed.get("size") + " bytes");
        }
        return 0;
    }

    private int publish(List<String> args) throws IOException, InterruptedException {
        List<String> positional = new ArrayList<>();
        List<String> parents = new ArrayList<>();
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        String actor = System.getProperty("user.name", "anonymous");
        for (String arg : args) {
            if (arg.startsWith("--parent=")) {
                parents.add(arg.substring("--parent=".length()));
            } else if (arg.startsWith("--label=")) {
                String pair = arg.substring("--label=".length());
                int equals = pair.indexOf('=');
                if (equals < 0) {
                    throw new IllegalArgumentException("--label needs key=value, got " + pair);
                }
                labels.put(pair.substring(0, equals), pair.substring(equals + 1));
            } else if (arg.startsWith("--actor=")) {
                actor = arg.substring("--actor=".length());
            } else {
                positional.add(arg);
            }
        }
        if (positional.size() != 2) {
            throw new IllegalArgumentException(
                    "publish <model@version> <digest> [--parent=m@v] [--label=k=v] [--actor=who]");
        }
        io.cairn.core.Ref ref = io.cairn.core.Ref.parse(positional.get(0));

        Json.Writer body = new Json.Writer()
                .field("artifact", positional.get(1))
                .field("actor", actor)
                .strings("parents", parents);
        body.object("labels", labels);

        Response response = send("POST",
                "/v1/models/" + ref.model() + "/versions/" + ref.version(), body.done(), false);
        if (response.status() >= 300) {
            return report(response);
        }
        printVersion(response.body());
        return 0;
    }

    private int stage(List<String> args) throws IOException, InterruptedException {
        io.cairn.core.Ref ref = io.cairn.core.Ref.parse(args.get(0));
        String body = new Json.Writer()
                .field("stage", args.get(1))
                .field("actor", System.getProperty("user.name", "anonymous"))
                .done();
        Response response = send("PUT",
                "/v1/models/" + ref.model() + "/versions/" + ref.version() + "/stage", body, true);
        if (response.status() >= 300) {
            return report(response);
        }
        printVersion(response.body());
        return 0;
    }

    private int get(String reference) throws IOException, InterruptedException {
        io.cairn.core.Ref ref = io.cairn.core.Ref.parse(reference);
        Response response = send("GET",
                "/v1/models/" + ref.model() + "/versions/" + ref.version(), null, false);
        if (response.status() >= 300) {
            return report(response);
        }
        printVersion(response.body());
        return 0;
    }

    private int production(String model) throws IOException, InterruptedException {
        Response response = send("GET", "/v1/models/" + model + "/production", null, false);
        if (response.status() >= 300) {
            return report(response);
        }
        printVersion(response.body());
        return 0;
    }

    private int list(String model) throws IOException, InterruptedException {
        if (model == null) {
            return status();
        }
        Response response = send("GET", "/v1/models/" + model + "/versions", null, false);
        if (response.status() >= 300) {
            return report(response);
        }
        if (json) {
            System.out.println(response.body());
            return 0;
        }
        Map<String, Object> parsed = Json.parseObject(response.body());
        System.out.printf("%-16s %-12s %-16s %s%n", "VERSION", "STAGE", "ARTIFACT", "LABELS");
        for (Object entry : (List<?>) parsed.get("versions")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> version = (Map<String, Object>) entry;
            boolean deleted = Boolean.TRUE.equals(version.get("deleted"));
            System.out.printf("%-16s %-12s %-16s %s%n",
                    version.get("version"),
                    deleted ? "deleted" : version.get("stage"),
                    shortDigest((String) version.get("artifact")),
                    version.get("labels"));
        }
        return 0;
    }

    private int lineage(String reference) throws IOException, InterruptedException {
        io.cairn.core.Ref ref = io.cairn.core.Ref.parse(reference);
        Response response = send("GET",
                "/v1/models/" + ref.model() + "/versions/" + ref.version() + "/lineage",
                null, false);
        if (response.status() >= 300) {
            return report(response);
        }
        if (json) {
            System.out.println(response.body());
            return 0;
        }
        Map<String, Object> parsed = Json.parseObject(response.body());
        System.out.println(parsed.get("ref"));
        List<?> ancestors = (List<?>) parsed.get("ancestors");
        for (int i = 0; i < ancestors.size(); i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> ancestor = (Map<String, Object>) ancestors.get(i);
            boolean last = i == ancestors.size() - 1;
            System.out.println((last ? "  └─ " : "  ├─ ")
                    + ancestor.get("ref")
                    + "  " + shortDigest((String) ancestor.get("artifact"))
                    + "  " + (Boolean.TRUE.equals(ancestor.get("deleted"))
                            ? "deleted" : ancestor.get("stage")));
        }
        if (ancestors.isEmpty()) {
            System.out.println("  (no declared ancestors)");
        }
        return 0;
    }

    private int remove(String reference) throws IOException, InterruptedException {
        io.cairn.core.Ref ref = io.cairn.core.Ref.parse(reference);
        Response response = send("DELETE",
                "/v1/models/" + ref.model() + "/versions/" + ref.version(), null, true);
        if (response.status() >= 300) {
            return report(response);
        }
        System.out.println(ref + " deleted");
        return 0;
    }

    private int effects() throws IOException, InterruptedException {
        Response response = send("GET", "/v1/effects", null, false);
        if (response.status() >= 300) {
            return report(response);
        }
        if (json) {
            System.out.println(response.body());
            return 0;
        }
        Map<String, Object> parsed = Json.parseObject(response.body());
        System.out.println("delivered through #" + parsed.get("dispatched_through")
                + ", next #" + parsed.get("next_sequence")
                + ", " + parsed.get("depth") + " pending");
        for (Object entry : (List<?>) parsed.get("pending")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> effect = (Map<String, Object>) entry;
            System.out.printf("  #%-6s %-20s %s%n",
                    effect.get("sequence"), effect.get("type"),
                    effect.get("ref") == null ? effect.get("model") : effect.get("ref"));
        }
        return 0;
    }

    private int verify() throws IOException, InterruptedException {
        Response response = send("GET", "/v1/verify", null, false);
        if (json) {
            System.out.println(response.body());
            return response.status() == 200 ? 0 : REFUSED;
        }
        Map<String, Object> parsed = Json.parseObject(response.body());
        boolean agrees = Boolean.TRUE.equals(parsed.get("agrees"));
        System.out.println((agrees ? "ok" : "MISMATCH")
                + "  served index " + parsed.get("served_index")
                + " digest " + shortHex((String) parsed.get("served_digest")));
        System.out.println("    re-derived from the log: index " + parsed.get("derived_index")
                + " digest " + shortHex((String) parsed.get("derived_digest")));
        return agrees ? 0 : REFUSED;
    }

    private int state(List<String> args) throws IOException, InterruptedException {
        String query = "";
        for (String arg : args) {
            if (arg.startsWith("--at=")) {
                query = "?at=" + arg.substring("--at=".length());
            }
        }
        Response response = send("GET", "/v1/state" + query, null, false);
        if (response.status() >= 300) {
            return report(response);
        }
        if (json) {
            System.out.println(response.body());
            return 0;
        }
        Map<String, Object> parsed = Json.parseObject(response.body());
        System.out.println("at log index " + parsed.get("at")
                + ", state " + shortHex((String) parsed.get("digest")));
        for (Object entry : (List<?>) parsed.get("models")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> model = (Map<String, Object>) entry;
            System.out.printf("  %-24s %s live, production %s%n",
                    model.get("model"), model.get("live"),
                    model.get("production") == null ? "-" : model.get("production"));
        }
        return 0;
    }

    private int fsck() throws IOException, InterruptedException {
        Response response = send("GET", "/v1/fsck", null, true);
        if (response.status() >= 300) {
            return report(response);
        }
        if (json) {
            System.out.println(response.body());
            return 0;
        }
        Map<String, Object> parsed = Json.parseObject(response.body());
        long orphans = (Long) parsed.get("orphans");
        System.out.println(orphans + " orphaned artifact(s), " + parsed.get("orphan_bytes")
                + " bytes");
        for (Object entry : (List<?>) parsed.get("artifacts")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> artifact = (Map<String, Object>) entry;
            System.out.println("  " + shortDigest((String) artifact.get("digest"))
                    + "  " + artifact.get("size") + " bytes");
        }
        if (orphans > 0) {
            System.out.println();
            System.out.println("These are bytes the registry has no live reference for. They are"
                    + " reported, never deleted:");
            System.out.println("an upload writes bytes before the command that records them, so a"
                    + " sweep that deleted");
            System.out.println("whatever it could not find a reference for would race every"
                    + " upload in flight.");
        }
        return 0;
    }

    // ---- plumbing ----------------------------------------------------------------------------

    private record Response(int status, String body) {}

    private Response send(String method, String path, String body, boolean admin)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(timeout)
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        authorize(request, admin);
        return exchange(request);
    }

    private void authorize(HttpRequest.Builder request, boolean admin) {
        String chosen = admin && adminToken != null ? adminToken : token;
        if (chosen != null) {
            request.header("Authorization", "Bearer " + chosen);
        }
        request.header("X-Cairn-Actor", System.getProperty("user.name", "anonymous"));
    }

    private Response exchange(HttpRequest.Builder request)
            throws IOException, InterruptedException {
        HttpResponse<String> response =
                http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private int report(Response response) {
        if (json) {
            System.out.println(response.body());
            return REFUSED;
        }
        try {
            Map<String, Object> parsed = Json.parseObject(response.body());
            System.err.println("cairnctl: " + parsed.get("error") + ": " + parsed.get("detail"));
        } catch (RuntimeException e) {
            System.err.println("cairnctl: HTTP " + response.status() + ": " + response.body());
        }
        return REFUSED;
    }

    private void printVersion(String body) {
        if (json) {
            System.out.println(body);
            return;
        }
        Map<String, Object> version = Json.parseObject(body);
        System.out.println(version.get("ref"));
        System.out.println("  artifact    " + version.get("artifact"));
        System.out.println("  stage       "
                + (Boolean.TRUE.equals(version.get("deleted"))
                        ? "deleted" : version.get("stage")));
        System.out.println("  published   " + version.get("published_by")
                + " at " + version.get("published_at"));
        Object parents = version.get("parents");
        if (parents instanceof List<?> list && !list.isEmpty()) {
            System.out.println("  parents     " + String.join(", ",
                    list.stream().map(String::valueOf).toList()));
        }
        Object labels = version.get("labels");
        if (labels instanceof Map<?, ?> map && !map.isEmpty()) {
            map.forEach((key, value) ->
                    System.out.printf("  %-11s %s%n", key, value));
        }
    }

    private static List<String> require(List<String> args, int count, String usage) {
        if (args.size() < count) {
            throw new IllegalArgumentException(usage);
        }
        return args;
    }

    private static String shortDigest(String digest) {
        return digest == null ? "-" : digest.substring(0, Math.min(digest.length(), 19));
    }

    private static String shortHex(String hex) {
        return hex == null ? "-" : hex.substring(0, Math.min(hex.length(), 12));
    }

    private static void usage() {
        System.out.println("cairnctl — talk to a cairn registry");
        System.out.println();
        System.out.println("  status                            models, and how far delivery is");
        System.out.println("  put <file>                        upload an artifact, print its digest");
        System.out.println("  publish <m@v> <digest> [...]      publish a version into STAGING");
        System.out.println("      --parent=<m@v>                declare an ancestor, repeatable");
        System.out.println("      --label=<k>=<v>               attach metadata, repeatable");
        System.out.println("  stage <m@v> <stage>               staging|production|archived|deprecated");
        System.out.println("  get <m@v>                         one version");
        System.out.println("  ls [model]                        versions of a model");
        System.out.println("  production <model>                what serving should load");
        System.out.println("  lineage <m@v>                     every declared ancestor");
        System.out.println("  rm <m@v>                          tombstone a version");
        System.out.println("  effects                           what is owed downstream");
        System.out.println("  verify                            re-derive from the log and compare");
        System.out.println("  state [--at=<index>]              the registry as it was");
        System.out.println("  fsck                              artifacts with no live reference");
        System.out.println();
        System.out.println("  --url=<base>                      default $CAIRN_URL"
                + " or http://127.0.0.1:9080");
        System.out.println("  --token=<secret>                  default $CAIRN_TOKEN");
        System.out.println("  --admin-token=<secret>            default $CAIRN_ADMIN_TOKEN");
        System.out.println("  --json                            print responses verbatim");
        System.out.println("  --timeout=<seconds>               wait for a response (30)");
        System.out.println();
        System.out.println("exit 0 success, 1 refused, 2 bad usage, 3 unreachable");
    }
}
