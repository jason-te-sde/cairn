package io.cairn.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.cairn.codec.Codec;
import io.cairn.core.Blob;
import io.cairn.core.Command;
import io.cairn.core.Digest;
import io.cairn.core.Effect;
import io.cairn.core.Model;
import io.cairn.core.ModelId;
import io.cairn.core.ModelVersion;
import io.cairn.core.Outcome;
import io.cairn.core.Ref;
import io.cairn.core.Registry;
import io.cairn.core.RejectionCode;
import io.cairn.core.SequencedEffect;
import io.cairn.core.Stage;
import io.cairn.core.VersionId;
import io.cairn.store.DigestMismatchException;
import io.cairn.store.NoSuchBlobException;
import io.cairn.store.StoreException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The HTTP surface.
 *
 * <pre>
 * PUT    /v1/artifacts                              upload; X-Cairn-Digest verifies
 * GET    /v1/artifacts/{digest}                     download
 * GET    /v1/models                                 every model
 * GET    /v1/models/{m}/versions                    every version of one
 * POST   /v1/models/{m}/versions/{v}                publish, into STAGING
 * GET    /v1/models/{m}/versions/{v}                one version
 * DELETE /v1/models/{m}/versions/{v}                tombstone            (admin)
 * PUT    /v1/models/{m}/versions/{v}/stage          promote or demote    (admin)
 * GET    /v1/models/{m}/versions/{v}/lineage        ancestry
 * GET    /v1/models/{m}/production                  what serving should load
 * GET    /v1/effects                                what is owed downstream
 * GET    /v1/verify                                 re-derive from the log and compare
 * GET    /v1/state?at=N                             the registry as it was at a log index
 * GET    /v1/fsck                                   artifacts with no live reference (admin)
 * GET    /healthz  /readyz  /metrics                no token required
 * </pre>
 *
 * <p>Two things about the design are worth reading rather than guessing at.
 *
 * <p><b>{@code /v1/models/{m}/production} exists on purpose.</b> It is the one question serving
 * infrastructure asks, and giving it a route means a serving tier does not have to list versions
 * and work out which one to trust — which is to say, does not have to reimplement the kernel's
 * rules in a client.
 *
 * <p><b>There is no route that proposes {@link Command.AckEffects}.</b> Not an omission. A
 * simulation run failed on exactly-once delivery because an acknowledgement from something that
 * had not delivered anything moved the watermark past effects nobody had sent. The kernel cannot
 * tell such an acknowledgement from a real one, so the guarantee holds only if the dispatcher is
 * the only thing that proposes one. {@code SECURITY.md} states it as a property of the deployment.
 */
final class Api implements HttpHandler {

    private static final Logger LOG = LoggerFactory.getLogger(Api.class);

    /** Largest request body the API will read, so a request cannot become an allocation. */
    private static final int MAX_BODY = Json.MAX_BYTES;

    private final Engine engine;
    private final ServerConfig config;

    Api(Engine engine, ServerConfig config) {
        this.engine = engine;
        this.config = config;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        try {
            route(exchange, method, path);
        } catch (IllegalArgumentException | Json.MalformedException e) {
            // A malformed identifier or body. Distinct from a rejection: this is "you sent
            // nonsense", which no amount of retrying will fix.
            respondError(exchange, 400, "malformed_request", e.getMessage());
        } catch (DigestMismatchException e) {
            respondError(exchange, 422, "digest_mismatch", e.getMessage());
        } catch (NoSuchBlobException e) {
            respondError(exchange, 404, "no_such_artifact", e.getMessage());
        } catch (StoreException e) {
            respondError(exchange, 409, "store_conflict", e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("{} {} failed", method, path, e);
            respondError(exchange, 500, "internal_error", "the request could not be completed");
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange, String method, String path) throws IOException {
        if (path.equals("/healthz")) {
            respondText(exchange, 200, "ok\n");
            return;
        }
        if (path.equals("/readyz")) {
            // Liveness and readiness answer different questions. This one says the kernel thread
            // is responsive and the state is readable, which is what a load balancer needs to know.
            Registry state = engine.state();
            respondText(exchange, 200, "ready index=" + state.appliedIndex()
                    + " state=" + Codec.stateFingerprint(state) + "\n");
            return;
        }
        if (path.equals("/metrics")) {
            respondText(exchange, 200, Metrics.render(engine.state(), engine.stats()));
            return;
        }

        if (!path.startsWith("/v1/")) {
            respondError(exchange, 404, "no_such_route", method + " " + path);
            return;
        }

        String[] parts = path.substring(1).split("/");
        boolean admin = isAdminRoute(method, parts);
        if (!authorized(exchange, admin)) {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
            respondError(exchange, 401, "unauthorized",
                    admin ? "this route needs the admin token" : "a bearer token is required");
            return;
        }

        // parts[0] is "v1"
        if (parts.length == 2 && parts[1].equals("artifacts") && method.equals("PUT")) {
            uploadArtifact(exchange);
            return;
        }
        if (parts.length == 3 && parts[1].equals("artifacts") && method.equals("GET")) {
            downloadArtifact(exchange, Digest.parse(decode(parts[2])));
            return;
        }
        if (parts.length == 2 && parts[1].equals("models") && method.equals("GET")) {
            listModels(exchange);
            return;
        }
        if (parts.length == 2 && parts[1].equals("effects") && method.equals("GET")) {
            listEffects(exchange);
            return;
        }
        if (parts.length == 2 && parts[1].equals("verify") && method.equals("GET")) {
            verify(exchange);
            return;
        }
        if (parts.length == 2 && parts[1].equals("fsck") && method.equals("GET")) {
            fsck(exchange);
            return;
        }
        if (parts.length == 2 && parts[1].equals("state") && method.equals("GET")) {
            stateAt(exchange);
            return;
        }
        if (parts.length == 4 && parts[1].equals("models") && parts[3].equals("versions")
                && method.equals("GET")) {
            listVersions(exchange, ModelId.of(decode(parts[2])));
            return;
        }
        if (parts.length == 4 && parts[1].equals("models") && parts[3].equals("production")
                && method.equals("GET")) {
            production(exchange, ModelId.of(decode(parts[2])));
            return;
        }
        if (parts.length == 5 && parts[1].equals("models") && parts[3].equals("versions")) {
            Ref ref = new Ref(ModelId.of(decode(parts[2])), VersionId.of(decode(parts[4])));
            switch (method) {
                case "GET" -> getVersion(exchange, ref);
                case "POST" -> publish(exchange, ref);
                case "DELETE" -> delete(exchange, ref);
                default -> respondError(exchange, 405, "method_not_allowed", method);
            }
            return;
        }
        if (parts.length == 6 && parts[1].equals("models") && parts[3].equals("versions")) {
            Ref ref = new Ref(ModelId.of(decode(parts[2])), VersionId.of(decode(parts[4])));
            if (parts[5].equals("stage") && method.equals("PUT")) {
                promote(exchange, ref);
                return;
            }
            if (parts[5].equals("lineage") && method.equals("GET")) {
                lineage(exchange, ref);
                return;
            }
        }
        respondError(exchange, 404, "no_such_route", method + " " + path);
    }

    // ---- routes ------------------------------------------------------------------------------

    private void uploadArtifact(HttpExchange exchange) throws IOException {
        String promised = exchange.getRequestHeaders().getFirst("X-Cairn-Digest");
        Digest expected = promised == null ? null : Digest.parse(promised);
        try (InputStream body = exchange.getRequestBody()) {
            Engine.Ingested ingested = engine.ingest(body, expected);
            if (!ingested.outcome().accepted()) {
                Outcome.Rejected refused = (Outcome.Rejected) ingested.outcome();
                respondError(exchange, statusFor(refused.code()),
                        refused.code().name().toLowerCase(), refused.detail());
                return;
            }
            respondJson(exchange, 201, new Json.Writer()
                    .field("digest", ingested.digest().toString())
                    .field("size", ingested.size())
                    .done());
        }
    }

    private void downloadArtifact(HttpExchange exchange, Digest digest) throws IOException {
        try (InputStream bytes = engine.blobs().open(digest)) {
            long size = engine.blobs().size(digest);
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().add("X-Cairn-Digest", digest.toString());
            // Cacheable forever: a digest names exactly one sequence of bytes, so the answer to
            // this request can never change.
            exchange.getResponseHeaders().add("Cache-Control", "public, max-age=31536000, immutable");
            exchange.sendResponseHeaders(200, size);
            try (OutputStream out = exchange.getResponseBody()) {
                bytes.transferTo(out);
            }
        }
    }

    private void listModels(HttpExchange exchange) throws IOException {
        Registry state = engine.state();
        List<String> rendered = new ArrayList<>(state.models().size());
        for (Model model : state.models().values()) {
            rendered.add(new Json.Writer()
                    .field("model", model.id().value())
                    .field("versions", model.versions().size())
                    .field("live", model.liveCount())
                    .field("production",
                            model.production().map(v -> v.version().value()).orElse(null))
                    .done());
        }
        respondJson(exchange, 200, new Json.Writer().raw("models", Json.array(rendered)).done());
    }

    private void listVersions(HttpExchange exchange, ModelId id) throws IOException {
        Model model = engine.state().model(id).orElse(null);
        if (model == null) {
            respondError(exchange, 404, "unknown_model", "no model named " + id);
            return;
        }
        List<String> rendered = new ArrayList<>();
        for (ModelVersion version : model.versions().values()) {
            rendered.add(renderVersion(id, version));
        }
        respondJson(exchange, 200, new Json.Writer()
                .field("model", id.value())
                .raw("versions", Json.array(rendered))
                .done());
    }

    private void getVersion(HttpExchange exchange, Ref ref) throws IOException {
        ModelVersion version = engine.state().version(ref).orElse(null);
        if (version == null) {
            respondError(exchange, 404, "unknown_version", "no version " + ref);
            return;
        }
        respondJson(exchange, 200, renderVersion(ref.model(), version));
    }

    private void production(HttpExchange exchange, ModelId id) throws IOException {
        Model model = engine.state().model(id).orElse(null);
        ModelVersion version = model == null ? null : model.production().orElse(null);
        if (version == null) {
            respondError(exchange, 404, "no_production_version",
                    model == null ? "no model named " + id : id + " has no production version");
            return;
        }
        respondJson(exchange, 200, renderVersion(id, version));
    }

    private void publish(HttpExchange exchange, Ref ref) throws IOException {
        Map<String, Object> body = Json.parseObject(readBody(exchange));
        Digest artifact = Digest.parse(Json.string(body, "artifact"));
        List<Ref> parents = new ArrayList<>();
        for (String parent : Json.strings(body, "parents")) {
            parents.add(Ref.parse(parent));
        }
        TreeMap<String, String> labels = new TreeMap<>(Json.stringMap(body, "labels"));
        String actor = actorOf(body);

        Outcome outcome = engine.propose(new Command.PublishVersion(
                ref, artifact, parents, labels, actor, System.currentTimeMillis()));
        respondOutcome(exchange, outcome, 201, () -> renderVersion(
                ref.model(), engine.state().version(ref).orElseThrow()));
    }

    private void promote(HttpExchange exchange, Ref ref) throws IOException {
        Map<String, Object> body = Json.parseObject(readBody(exchange));
        String requested = Json.string(body, "stage");
        Stage target;
        try {
            target = Stage.valueOf(requested.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "'" + requested + "' is not a stage; expected one of "
                            + java.util.Arrays.toString(Stage.values()));
        }
        Outcome outcome = engine.propose(
                new Command.Promote(ref, target, actorOf(body), System.currentTimeMillis()));
        respondOutcome(exchange, outcome, 200, () -> renderVersion(
                ref.model(), engine.state().version(ref).orElseThrow()));
    }

    private void delete(HttpExchange exchange, Ref ref) throws IOException {
        String actor = exchange.getRequestHeaders().getFirst("X-Cairn-Actor");
        Outcome outcome = engine.propose(new Command.DeleteVersion(
                ref, actor == null || actor.isBlank() ? "anonymous" : actor,
                System.currentTimeMillis()));
        respondOutcome(exchange, outcome, 200, () -> new Json.Writer()
                .field("ref", ref.toString())
                .field("deleted", true)
                .done());
    }

    private void lineage(HttpExchange exchange, Ref ref) throws IOException {
        Registry state = engine.state();
        if (state.version(ref).isEmpty()) {
            respondError(exchange, 404, "unknown_version", "no version " + ref);
            return;
        }
        // Breadth-first over the parent relation, deduplicated: lineage is a DAG rather than a
        // tree, so a shared ancestor would otherwise appear once per path that reaches it.
        Set<Ref> seen = new LinkedHashSet<>();
        List<Ref> frontier = new ArrayList<>(List.of(ref));
        while (!frontier.isEmpty()) {
            List<Ref> next = new ArrayList<>();
            for (Ref current : frontier) {
                ModelVersion version = state.version(current).orElse(null);
                if (version == null) {
                    continue;
                }
                for (Ref parent : version.parents()) {
                    if (seen.add(parent)) {
                        next.add(parent);
                    }
                }
            }
            frontier = next;
        }

        List<String> ancestors = new ArrayList<>(seen.size());
        for (Ref ancestor : seen) {
            ModelVersion version = state.version(ancestor).orElseThrow();
            ancestors.add(new Json.Writer()
                    .field("ref", ancestor.toString())
                    .field("artifact", version.artifact().toString())
                    .field("stage", version.stage().name().toLowerCase())
                    .field("deleted", version.deleted())
                    .strings("parents", version.parents().stream().map(Ref::toString).toList())
                    .done());
        }
        respondJson(exchange, 200, new Json.Writer()
                .field("ref", ref.toString())
                .raw("ancestors", Json.array(ancestors))
                .field("depth", ancestors.size())
                .done());
    }

    private void listEffects(HttpExchange exchange) throws IOException {
        Registry state = engine.state();
        List<String> pending = new ArrayList<>(state.outbox().size());
        for (SequencedEffect effect : state.outbox()) {
            pending.add(renderEffect(effect));
        }
        respondJson(exchange, 200, new Json.Writer()
                .field("dispatched_through", state.dispatchedThrough())
                .field("next_sequence", state.nextEffectSeq())
                .field("depth", state.outboxDepth())
                .raw("pending", Json.array(pending))
                .done());
    }

    private void verify(HttpExchange exchange) throws IOException {
        Engine.Verification result = engine.verify();
        respondJson(exchange, result.agrees() ? 200 : 500, new Json.Writer()
                .field("agrees", result.agrees())
                .field("served_index", result.servedIndex())
                .field("derived_index", result.derivedIndex())
                .field("served_digest", result.servedDigest())
                .field("derived_digest", result.derivedDigest())
                .done());
    }

    /**
     * Artifacts on disk the registry has no live reference for.
     *
     * <p>Reported, never deleted, and the direction of the comparison is the point. The registry
     * decides what should exist; a sweep that deleted whatever it could not find a reference for
     * would race every upload in flight, because bytes are written before the command that records
     * them. {@code docs/operations.md} says what to do with the list.
     */
    private void fsck(HttpExchange exchange) throws IOException {
        List<Digest> orphans = engine.orphanedArtifacts();
        List<String> rendered = new ArrayList<>(orphans.size());
        long bytes = 0;
        for (Digest digest : orphans) {
            long size = engine.blobs().size(digest);
            bytes += size;
            rendered.add(new Json.Writer()
                    .field("digest", digest.toString())
                    .field("size", size)
                    .done());
        }
        respondJson(exchange, 200, new Json.Writer()
                .field("orphans", orphans.size())
                .field("orphan_bytes", bytes)
                .raw("artifacts", Json.array(rendered))
                .done());
    }

    private void stateAt(HttpExchange exchange) throws IOException {
        // Parsed out of the whole query rather than assumed to be first, so `?foo=1&at=2` works.
        // Long.parseLong throws NumberFormatException, which is an IllegalArgumentException, which
        // the handler maps to 400 — the right answer for `?at=banana`.
        long at = engine.state().appliedIndex();
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String parameter : query.split("&")) {
                if (parameter.startsWith("at=")) {
                    at = Long.parseLong(parameter.substring(3));
                }
            }
        }
        if (at < 0) {
            throw new IllegalArgumentException("at must not be negative: " + at);
        }
        Registry historic = engine.replayTo(at);
        List<String> models = new ArrayList<>();
        for (Model model : historic.models().values()) {
            models.add(new Json.Writer()
                    .field("model", model.id().value())
                    .field("live", model.liveCount())
                    .field("production",
                            model.production().map(v -> v.version().value()).orElse(null))
                    .done());
        }
        respondJson(exchange, 200, new Json.Writer()
                .field("at", historic.appliedIndex())
                .field("digest", Codec.stateDigestHex(historic))
                .raw("models", Json.array(models))
                .done());
    }

    // ---- rendering ---------------------------------------------------------------------------

    static String renderVersion(ModelId model, ModelVersion version) {
        return new Json.Writer()
                .field("ref", new Ref(model, version.version()).toString())
                .field("model", model.value())
                .field("version", version.version().value())
                .field("artifact", version.artifact().toString())
                .field("stage", version.stage().name().toLowerCase())
                .field("deleted", version.deleted())
                .strings("parents", version.parents().stream().map(Ref::toString).toList())
                .object("labels", version.labels())
                .field("published_at", version.publishedAt())
                .field("published_by", version.publishedBy())
                .done();
    }

    static String renderBlob(Blob blob) {
        return new Json.Writer()
                .field("digest", blob.digest().toString())
                .field("size", blob.size())
                .field("references", blob.refCount())
                .field("present", blob.present())
                .field("collect_sequence", blob.collectSeq())
                .done();
    }

    /**
     * One effect as a JSON object.
     *
     * <p>Every record carries its sequence number, which is what makes the JSON-lines effect log a
     * queue a consumer can deduplicate against rather than a debugging aid.
     */
    static String renderEffect(SequencedEffect sequenced) {
        Json.Writer out = new Json.Writer()
                .field("sequence", sequenced.seq())
                .field("model", sequenced.effect().model().value());
        return switch (sequenced.effect()) {
            case Effect.VersionPublished e -> out
                    .field("type", "version_published")
                    .field("ref", e.ref().toString())
                    .field("artifact", e.artifact().toString())
                    .field("published_at", e.publishedAt())
                    .field("published_by", e.publishedBy())
                    .done();
            case Effect.StageChanged e -> out
                    .field("type", "stage_changed")
                    .field("ref", e.ref().toString())
                    .field("from", e.from().name().toLowerCase())
                    .field("to", e.to().name().toLowerCase())
                    .field("actor", e.actor())
                    .field("at", e.atMillis())
                    .done();
            case Effect.VersionDeleted e -> out
                    .field("type", "version_deleted")
                    .field("ref", e.ref().toString())
                    .field("actor", e.actor())
                    .field("at", e.atMillis())
                    .done();
            case Effect.ArtifactCollected e -> out
                    .field("type", "artifact_collected")
                    .field("artifact", e.digest().toString())
                    .field("size", e.size())
                    .done();
            case Effect.ProductionChanged e -> out
                    .field("type", "production_changed")
                    .field("production", e.production() == null ? null : e.production().value())
                    .done();
        };
    }

    // ---- plumbing ----------------------------------------------------------------------------

    /**
     * Which routes need the admin token.
     *
     * <p>Deleting a version and changing what production loads are not the same privilege as
     * publishing one. Publishing adds something nobody is using yet; the other two change what is
     * already running.
     */
    private static boolean isAdminRoute(String method, String[] parts) {
        if (method.equals("DELETE")) {
            return true;
        }
        if (parts.length == 2 && parts[1].equals("fsck")) {
            return true;
        }
        return method.equals("PUT") && parts.length == 6 && parts[5].equals("stage");
    }

    private boolean authorized(HttpExchange exchange, boolean admin) {
        String required = admin && config.adminToken() != null
                ? config.adminToken()
                : config.token();
        if (required == null) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        String offered = header.substring("Bearer ".length());
        // Constant-time, so a token cannot be recovered one character at a time by measuring how
        // long the comparison took.
        return java.security.MessageDigest.isEqual(
                offered.getBytes(StandardCharsets.UTF_8),
                required.getBytes(StandardCharsets.UTF_8));
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            byte[] bytes = body.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) {
                throw new Json.MalformedException(
                        "the body is larger than the " + MAX_BODY + " byte limit");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String actorOf(Map<String, Object> body) {
        String actor = Json.stringOrNull(body, "actor");
        return actor == null || actor.isBlank() ? "anonymous" : actor;
    }

    /**
     * Maps a rejection to a status code.
     *
     * <p>The split matters to a client: 404 means the thing is not there, 409 means what you asked
     * for conflicts with reality, and 503 means try again later. Collapsing them into one status is
     * how a client ends up retrying something that will never succeed — or giving up on something
     * that would have worked in a second.
     */
    private static int statusFor(RejectionCode code) {
        return switch (code) {
            case UNKNOWN_MODEL, UNKNOWN_VERSION, UNKNOWN_PARENT, ARTIFACT_MISSING -> 404;
            case COLLECTION_PENDING -> 503;
            default -> 409;
        };
    }

    private void respondOutcome(
            HttpExchange exchange, Outcome outcome, int okStatus, Supplier<String> body)
            throws IOException {
        switch (outcome) {
            case Outcome.Applied applied -> respondJson(
                    exchange,
                    applied.kind() == Outcome.Applied.Kind.NEW ? okStatus : 200,
                    body.get());
            case Outcome.Rejected refused -> respondError(
                    exchange, statusFor(refused.code()),
                    refused.code().name().toLowerCase(), refused.detail());
        }
    }

    /** A supplier that may be skipped, so a rejection does not have to build a success body. */
    @FunctionalInterface
    private interface Supplier<T> {
        T get();
    }

    private static void respondJson(HttpExchange exchange, int status, String json)
            throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void respondText(HttpExchange exchange, int status, String text)
            throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void respondError(HttpExchange exchange, int status, String code, String detail)
            throws IOException {
        respondJson(exchange, status, new Json.Writer()
                .field("error", code)
                .field("detail", detail == null ? "" : detail)
                .done());
    }

    private static String decode(String segment) {
        return java.net.URLDecoder.decode(segment, StandardCharsets.UTF_8);
    }
}
