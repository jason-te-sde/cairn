package io.cairn.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The HTTP API over real sockets and a real filesystem.
 *
 * <p>This is the layer where a codec bug, a status-code mistake or a leaked file handle shows up,
 * and all three are invisible to anything in-process. The assertions are mostly about status codes,
 * because the difference between 404, 409 and 503 is the difference between a client retrying
 * forever and a client giving up on something that would have worked.
 */
class ApiTest {

    @TempDir
    Path directory;

    private TestRegistry registry;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws IOException {
        registry = new TestRegistry(directory);
    }

    @AfterEach
    void stop() throws IOException {
        registry.close();
    }

    // ---- helpers -----------------------------------------------------------------------------

    private HttpResponse<String> request(String method, String path, String body, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(registry.base() + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return request("GET", path, null, null);
    }

    private String upload(String content) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(registry.base() + "/v1/artifacts"))
                        .PUT(HttpRequest.BodyPublishers.ofString(content))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), response.body());
        return Json.string(Json.parseObject(response.body()), "digest");
    }

    private static String digestOf(String content) {
        try {
            return io.cairn.core.Digest.ofSha256(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8))).toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private HttpResponse<String> publish(String ref, String digest)
            throws IOException, InterruptedException {
        io.cairn.core.Ref parsed = io.cairn.core.Ref.parse(ref);
        return request("POST",
                "/v1/models/" + parsed.model() + "/versions/" + parsed.version(),
                new Json.Writer().field("artifact", digest).field("actor", "alice").done(),
                null);
    }

    private HttpResponse<String> stage(String ref, String target)
            throws IOException, InterruptedException {
        io.cairn.core.Ref parsed = io.cairn.core.Ref.parse(ref);
        return request("PUT",
                "/v1/models/" + parsed.model() + "/versions/" + parsed.version() + "/stage",
                new Json.Writer().field("stage", target).field("actor", "alice").done(),
                null);
    }

    // ---- the happy path ----------------------------------------------------------------------

    @Test
    void anArtifactIsUploadedAndComesBackByteForByte() throws Exception {
        String digest = upload("the weights");
        assertEquals(digestOf("the weights"), digest);

        HttpResponse<String> download = get("/v1/artifacts/" + digest);
        assertEquals(200, download.statusCode());
        assertEquals("the weights", download.body());
        assertEquals(digest, download.headers().firstValue("X-Cairn-Digest").orElseThrow());
        assertTrue(download.headers().firstValue("Cache-Control").orElseThrow()
                        .contains("immutable"),
                "a digest names one sequence of bytes, so the answer can never change");
    }

    @Test
    void aPromisedDigestIsVerified() throws Exception {
        HttpResponse<String> refused = http.send(
                HttpRequest.newBuilder(URI.create(registry.base() + "/v1/artifacts"))
                        .PUT(HttpRequest.BodyPublishers.ofString("the weights"))
                        .header("X-Cairn-Digest", digestOf("something else"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(422, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("digest_mismatch"), refused.body());
        // And nothing was stored under either name.
        assertEquals(404, get("/v1/artifacts/" + digestOf("something else")).statusCode());
        assertEquals(404, get("/v1/artifacts/" + digestOf("the weights")).statusCode());
    }

    @Test
    void aPublishedVersionIsStagedAndReadable() throws Exception {
        String digest = upload("weights v1");
        HttpResponse<String> published = publish("fraud@1.0.0", digest);

        assertEquals(201, published.statusCode(), published.body());
        Map<String, Object> version = Json.parseObject(published.body());
        assertEquals("staging", version.get("stage"));
        assertEquals(digest, version.get("artifact"));
        assertEquals("alice", version.get("published_by"));

        assertEquals(200, get("/v1/models/fraud/versions/1.0.0").statusCode());
        assertEquals(404, get("/v1/models/fraud/production").statusCode(),
                "publishing must not put a version into production");
    }

    @Test
    void promotingMakesItTheProductionVersion() throws Exception {
        publish("fraud@1.0.0", upload("weights v1"));
        assertEquals(200, stage("fraud@1.0.0", "production").statusCode());

        HttpResponse<String> production = get("/v1/models/fraud/production");
        assertEquals(200, production.statusCode(), production.body());
        assertEquals("1.0.0", Json.parseObject(production.body()).get("version"));
    }

    @Test
    void promotingASuccessorArchivesTheIncumbent() throws Exception {
        publish("fraud@1.0.0", upload("weights v1"));
        publish("fraud@2.0.0", upload("weights v2"));
        stage("fraud@1.0.0", "production");
        stage("fraud@2.0.0", "production");

        assertEquals("2.0.0",
                Json.parseObject(get("/v1/models/fraud/production").body()).get("version"));
        assertEquals("archived",
                Json.parseObject(get("/v1/models/fraud/versions/1.0.0").body()).get("stage"));
    }

    @Test
    void lineageComesBackAsADagWithoutDuplicates() throws Exception {
        publish("embeddings@1.0.0", upload("embeddings"));
        String base = upload("base");
        io.cairn.core.Ref child = io.cairn.core.Ref.parse("fraud@1.0.0");
        request("POST", "/v1/models/" + child.model() + "/versions/" + child.version(),
                new Json.Writer()
                        .field("artifact", base)
                        .field("actor", "alice")
                        .strings("parents", List.of("embeddings@1.0.0"))
                        .done(),
                null);
        String tuned = upload("tuned");
        request("POST", "/v1/models/fraud/versions/2.0.0",
                new Json.Writer()
                        .field("artifact", tuned)
                        .field("actor", "alice")
                        .strings("parents", List.of("fraud@1.0.0", "embeddings@1.0.0"))
                        .done(),
                null);

        HttpResponse<String> lineage = get("/v1/models/fraud/versions/2.0.0/lineage");
        assertEquals(200, lineage.statusCode(), lineage.body());
        Map<String, Object> parsed = Json.parseObject(lineage.body());
        assertEquals(2L, parsed.get("depth"),
                "the shared ancestor must appear once, not once per path: " + lineage.body());
    }

    @Test
    void deletingReleasesTheArtifactAndTheCollectorRemovesIt() throws Exception {
        String digest = upload("disposable");
        publish("fraud@1.0.0", digest);

        assertEquals(200, request("DELETE", "/v1/models/fraud/versions/1.0.0", null, null)
                .statusCode());

        // The dispatcher runs on the command thread after each command, so by the time the delete
        // has returned the collection order has been delivered and the bytes are gone.
        assertEquals(404, get("/v1/artifacts/" + digest).statusCode(),
                "the collector should have removed the artifact");
        assertTrue(Boolean.TRUE.equals(
                Json.parseObject(get("/v1/models/fraud/versions/1.0.0").body()).get("deleted")));
    }

    // ---- the refusals ------------------------------------------------------------------------

    @Test
    void publishingAgainstAnUnknownArtifactIsFourOhFour() throws Exception {
        HttpResponse<String> refused = publish("fraud@1.0.0", digestOf("never uploaded"));
        assertEquals(404, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("artifact_missing"), refused.body());
    }

    @Test
    void republishingWithDifferentContentIsFourOhNine() throws Exception {
        publish("fraud@1.0.0", upload("weights v1"));
        HttpResponse<String> refused = publish("fraud@1.0.0", upload("weights v2"));

        assertEquals(409, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("immutable_version"), refused.body());
    }

    @Test
    void republishingTheSameThingIsTwoHundredNotTwoOhOne() throws Exception {
        String digest = upload("weights v1");
        assertEquals(201, publish("fraud@1.0.0", digest).statusCode());
        HttpResponse<String> retry = publish("fraud@1.0.0", digest);

        assertEquals(200, retry.statusCode(), retry.body());
        // The distinction is the whole point of a retriable API: 201 means it was created now, 200
        // means it already was, and a client on a flaky network needs to be able to tell.
    }

    @Test
    void anIllegalStageTransitionIsFourOhNine() throws Exception {
        publish("fraud@1.0.0", upload("weights v1"));
        HttpResponse<String> refused = stage("fraud@1.0.0", "deprecated");
        assertEquals(409, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("illegal_transition"), refused.body());
    }

    @Test
    void deletingTheProductionVersionIsFourOhNine() throws Exception {
        publish("fraud@1.0.0", upload("weights v1"));
        stage("fraud@1.0.0", "production");

        HttpResponse<String> refused =
                request("DELETE", "/v1/models/fraud/versions/1.0.0", null, null);
        assertEquals(409, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("production_version"), refused.body());
    }

    @Test
    void aMalformedIdentifierIsFourHundredRatherThanFourOhFour() throws Exception {
        // "you sent nonsense" and "you sent something reasonable that is not here" are different
        // problems for a client, and collapsing them is how a retry loop is born.
        HttpResponse<String> refused = get("/v1/models/Fraud/versions/1.0.0");
        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("malformed_request"), refused.body());
    }

    @Test
    void aMalformedBodyIsFourHundred() throws Exception {
        HttpResponse<String> refused = request("POST", "/v1/models/fraud/versions/1.0.0",
                "{\"artifact\": ", null);
        assertEquals(400, refused.statusCode(), refused.body());
    }

    @Test
    void anUnknownStageNameSaysWhatIsAllowed() throws Exception {
        publish("fraud@1.0.0", upload("weights v1"));
        HttpResponse<String> refused = stage("fraud@1.0.0", "live");
        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("STAGING"), refused.body());
    }

    @Test
    void anUnknownRouteIsFourOhFour() throws Exception {
        assertEquals(404, get("/v1/nonsense").statusCode());
        assertEquals(404, get("/nonsense").statusCode());
    }

    // ---- operations --------------------------------------------------------------------------

    @Test
    void healthAndReadinessAnswerDifferentQuestions() throws Exception {
        assertEquals(200, get("/healthz").statusCode());
        HttpResponse<String> ready = get("/readyz");
        assertEquals(200, ready.statusCode());
        assertTrue(ready.body().contains("index="), ready.body());
        assertTrue(ready.body().contains("state="), ready.body());
    }

    @Test
    void metricsAreScrapeableAndCarryTheNumbersThatMatter() throws Exception {
        publish("fraud@1.0.0", upload("weights v1"));
        stage("fraud@1.0.0", "production");
        publish("fraud@1.0.0", digestOf("never uploaded"));

        String metrics = get("/metrics").body();
        assertTrue(metrics.contains("cairn_outbox_depth"), metrics);
        assertTrue(metrics.contains("cairn_versions_by_stage{stage=\"production\"} 1"), metrics);
        assertTrue(metrics.contains("cairn_effects_deduplicated_total"), metrics);
        assertTrue(metrics.contains("cairn_commands_rejected_total{code=\"artifact_missing\"} 1"),
                metrics);
        assertTrue(metrics.contains("# TYPE cairn_commands_applied_total counter"), metrics);
    }

    @Test
    void verifyReDerivesTheStateFromTheLogAndAgrees() throws Exception {
        publish("fraud@1.0.0", upload("weights v1"));
        stage("fraud@1.0.0", "production");

        HttpResponse<String> response = get("/v1/verify");
        assertEquals(200, response.statusCode(), response.body());
        Map<String, Object> parsed = Json.parseObject(response.body());
        assertEquals(Boolean.TRUE, parsed.get("agrees"));
        assertEquals(parsed.get("served_digest"), parsed.get("derived_digest"));
        assertEquals(parsed.get("served_index"), parsed.get("derived_index"));
    }

    @Test
    void theStateAtAnEarlierIndexIsTheStateItWasThen() throws Exception {
        publish("fraud@1.0.0", upload("weights v1"));
        long beforePromotion = registry.engine().state().appliedIndex();
        stage("fraud@1.0.0", "production");

        Map<String, Object> now = Json.parseObject(get("/v1/state").body());
        Map<String, Object> then = Json.parseObject(get("/v1/state?at=" + beforePromotion).body());

        assertEquals(beforePromotion, then.get("at"));
        assertNotEquals(now.get("digest"), then.get("digest"));
        @SuppressWarnings("unchecked")
        Map<String, Object> model = (Map<String, Object>) ((List<?>) then.get("models")).get(0);
        assertEquals(null, model.get("production"),
                "at that index nothing was in production yet");
    }

    @Test
    void theEffectsRouteShowsWhatIsOwed() throws Exception {
        publish("fraud@1.0.0", upload("weights v1"));
        Map<String, Object> effects = Json.parseObject(get("/v1/effects").body());

        assertEquals(0L, effects.get("depth"),
                "the dispatcher runs after each command, so nothing should be owed");
        assertTrue((Long) effects.get("dispatched_through") > 0, effects.toString());
    }

    @Test
    void fsckReportsOrphansAndDeletesNothing() throws Exception {
        // An upload that is never published is an orphan by design: bytes are written before the
        // command that records them, which is what makes a digest trustworthy.
        String orphan = upload("uploaded and forgotten");

        Map<String, Object> report = Json.parseObject(get("/v1/fsck").body());
        assertEquals(0L, report.get("orphans"),
                "an ingested artifact is recorded, so it is not an orphan");
        assertEquals(200, get("/v1/artifacts/" + orphan).statusCode(),
                "and fsck must not have deleted it");
    }

    // ---- authorization -----------------------------------------------------------------------

    @Test
    void tokensAreRequiredWhenConfiguredAndAdminRoutesNeedTheAdminToken() throws Exception {
        registry.close();
        registry = new TestRegistry(directory.resolve("secured"), "client", "admin");

        assertEquals(401, get("/v1/models").statusCode());
        assertEquals(401, request("GET", "/v1/models", null, "wrong").statusCode());
        assertEquals(200, request("GET", "/v1/models", null, "client").statusCode());

        String digest = Json.string(Json.parseObject(http.send(
                HttpRequest.newBuilder(URI.create(registry.base() + "/v1/artifacts"))
                        .PUT(HttpRequest.BodyPublishers.ofString("weights"))
                        .header("Authorization", "Bearer client")
                        .build(),
                HttpResponse.BodyHandlers.ofString()).body()), "digest");
        assertEquals(201, request("POST", "/v1/models/fraud/versions/1.0.0",
                new Json.Writer().field("artifact", digest).done(), "client").statusCode());

        // Ejecting a version is not the same privilege as publishing one.
        assertEquals(401,
                request("DELETE", "/v1/models/fraud/versions/1.0.0", null, "client").statusCode());
        assertEquals(200,
                request("DELETE", "/v1/models/fraud/versions/1.0.0", null, "admin").statusCode());
    }

    @Test
    void healthEndpointsDoNotNeedAToken() throws Exception {
        registry.close();
        registry = new TestRegistry(directory.resolve("secured2"), "client", "admin");

        assertEquals(200, get("/healthz").statusCode());
        assertEquals(200, get("/readyz").statusCode());
        assertEquals(200, get("/metrics").statusCode(),
                "a scraper should not need the token that can publish a model");
    }

    @Test
    void aMissingAuthorizationHeaderAdvertisesTheScheme() throws Exception {
        registry.close();
        registry = new TestRegistry(directory.resolve("secured3"), "client", "admin");

        HttpResponse<String> refused = get("/v1/models");
        assertEquals(401, refused.statusCode());
        assertEquals("Bearer", refused.headers().firstValue("WWW-Authenticate").orElseThrow());
    }

    // ---- durability --------------------------------------------------------------------------

    @Test
    void everythingSurvivesARestart() throws Exception {
        String digest = upload("weights v1");
        publish("fraud@1.0.0", digest);
        stage("fraud@1.0.0", "production");
        String before = Json.parseObject(get("/v1/state").body()).get("digest").toString();

        registry.close();
        registry = new TestRegistry(directory);

        assertEquals(before, Json.parseObject(get("/v1/state").body()).get("digest"),
                "the registry came back different");
        assertEquals("1.0.0",
                Json.parseObject(get("/v1/models/fraud/production").body()).get("version"));
        assertEquals(200, get("/v1/artifacts/" + digest).statusCode());
        assertFalse(get("/v1/verify").body().contains("false"), get("/v1/verify").body());
    }

    @Test
    void aCheckpointChangesNothingObservable() throws Exception {
        publish("fraud@1.0.0", upload("weights v1"));
        stage("fraud@1.0.0", "production");
        String before = Json.parseObject(get("/v1/state").body()).get("digest").toString();

        registry.engine().checkpoint();
        registry.close();
        registry = new TestRegistry(directory);

        assertEquals(before, Json.parseObject(get("/v1/state").body()).get("digest"));
        assertEquals(Boolean.TRUE, Json.parseObject(get("/v1/verify").body()).get("agrees"));
    }
}
