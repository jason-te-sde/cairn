package io.cairn.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real client against a real server, with its output captured.
 *
 * <p>Worth testing rather than eyeballing, for two reasons. The exit codes are a contract — a
 * deployment script has to be able to tell "already published" from "registry unreachable" — and
 * the formatting is the part a person actually sees, which means it is the part where a wrong
 * column or a swallowed error message does real damage.
 */
class CliTest {

    @TempDir
    Path directory;

    private TestRegistry registry;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void start() throws IOException {
        registry = new TestRegistry(directory);
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void stop() throws IOException {
        System.setOut(originalOut);
        System.setErr(originalErr);
        registry.close();
    }

    private int run(String... args) {
        String[] withUrl = new String[args.length + 1];
        withUrl[0] = "--url=" + registry.base();
        System.arraycopy(args, 0, withUrl, 1, args.length);
        return Cli.execute(withUrl);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private String upload(String content) throws IOException {
        Path file = Files.createTempFile(directory, "artifact-", ".bin");
        Files.writeString(file, content);
        out.reset();
        assertEquals(0, run("put", file.toString()), stderr());
        return stdout().trim().split("\\s+")[0];
    }

    @Test
    void statusOnAnEmptyRegistrySaysSoRatherThanPrintingAnEmptyTable() {
        assertEquals(0, run("status"), stderr());
        assertTrue(stdout().contains("no models"), stdout());
        assertTrue(stdout().contains("ready index=0"), stdout());
    }

    @Test
    void theWholeLifecycleWorksFromTheCommandLine() throws IOException {
        String digest = upload("the weights");
        assertTrue(digest.startsWith("sha256:"), digest);

        out.reset();
        assertEquals(0, run("publish", "fraud@1.0.0", digest, "--label=owner=risk"), stderr());
        assertTrue(stdout().contains("fraud@1.0.0"), stdout());
        assertTrue(stdout().contains("staging"), stdout());
        assertTrue(stdout().contains("owner"), stdout());

        out.reset();
        assertEquals(0, run("stage", "fraud@1.0.0", "production"), stderr());
        assertTrue(stdout().contains("production"), stdout());

        out.reset();
        assertEquals(0, run("production", "fraud"), stderr());
        assertTrue(stdout().contains("fraud@1.0.0"), stdout());

        out.reset();
        assertEquals(0, run("ls", "fraud"), stderr());
        assertTrue(stdout().contains("VERSION"), stdout());
        assertTrue(stdout().contains("1.0.0"), stdout());
    }

    @Test
    void lineagePrintsATree() throws IOException {
        String base = upload("embeddings");
        run("publish", "embeddings@1.0.0", base);
        String tuned = upload("fraud weights");
        run("publish", "fraud@1.0.0", tuned, "--parent=embeddings@1.0.0");

        out.reset();
        assertEquals(0, run("lineage", "fraud@1.0.0"), stderr());
        assertTrue(stdout().contains("fraud@1.0.0"), stdout());
        assertTrue(stdout().contains("embeddings@1.0.0"), stdout());
        assertTrue(stdout().contains("└─") || stdout().contains("├─"), stdout());
    }

    @Test
    void aVersionWithNoAncestorsSaysSoRatherThanPrintingNothing() throws IOException {
        String digest = upload("standalone");
        run("publish", "fraud@1.0.0", digest);

        out.reset();
        assertEquals(0, run("lineage", "fraud@1.0.0"), stderr());
        assertTrue(stdout().contains("no declared ancestors"), stdout());
    }

    @Test
    void aRefusalExitsOneAndExplainsItself() throws IOException {
        String first = upload("weights v1");
        run("publish", "fraud@1.0.0", first);
        String second = upload("weights v2");

        err.reset();
        assertEquals(1, run("publish", "fraud@1.0.0", second));
        assertTrue(stderr().contains("immutable_version"), stderr());
        assertTrue(stderr().contains("cannot be changed"), stderr());
    }

    @Test
    void badUsageExitsTwo() {
        assertEquals(2, run("publish", "only-one-argument"));
        assertTrue(stderr().contains("publish <model@version>"), stderr());

        err.reset();
        assertEquals(2, run("nonsense"));
        assertTrue(stderr().contains("unknown command"), stderr());

        err.reset();
        assertEquals(2, run("get", "not-a-reference"));
        assertTrue(stderr().contains("model@version"), stderr());
    }

    @Test
    void anUnreachableRegistryExitsThree() {
        // A deployment script has to be able to tell this from a refusal: one means retry, the
        // other means the request will never succeed.
        assertEquals(3, Cli.execute(new String[] {"--url=http://127.0.0.1:1", "status"}));
        assertTrue(stderr().contains("cannot reach"), stderr());
    }

    @Test
    void helpExitsZeroAndNoArgumentsExitsTwo() {
        assertEquals(0, run("help"));
        assertTrue(stdout().contains("cairnctl"), stdout());
        assertTrue(stdout().contains("exit 0 success, 1 refused, 2 bad usage, 3 unreachable"),
                stdout());

        out.reset();
        assertEquals(2, Cli.execute(new String[0]));
    }

    @Test
    void jsonModePrintsTheResponseVerbatim() throws IOException {
        String digest = upload("the weights");
        out.reset();
        assertEquals(0, run("--json", "publish", "fraud@1.0.0", digest), stderr());
        assertTrue(stdout().trim().startsWith("{"), stdout());
        assertTrue(stdout().contains("\"ref\":\"fraud@1.0.0\""), stdout());
    }

    @Test
    void verifyReportsAgreementAndExitsZero() throws IOException {
        String digest = upload("the weights");
        run("publish", "fraud@1.0.0", digest);

        out.reset();
        assertEquals(0, run("verify"), stderr());
        assertTrue(stdout().startsWith("ok"), stdout());
        assertTrue(stdout().contains("re-derived from the log"), stdout());
    }

    @Test
    void stateCanBeAskedForAnEarlierIndex() throws IOException {
        String digest = upload("the weights");
        run("publish", "fraud@1.0.0", digest);
        long before = registry.engine().state().appliedIndex();
        run("stage", "fraud@1.0.0", "production");

        out.reset();
        assertEquals(0, run("state", "--at=" + before), stderr());
        assertTrue(stdout().contains("at log index " + before), stdout());
        assertTrue(stdout().contains("production -"), stdout());

        out.reset();
        assertEquals(0, run("state"), stderr());
        assertTrue(stdout().contains("production 1.0.0"), stdout());
    }

    @Test
    void effectsShowsTheStreamAndItsWatermark() throws IOException {
        String digest = upload("the weights");
        run("publish", "fraud@1.0.0", digest);

        out.reset();
        assertEquals(0, run("effects"), stderr());
        assertTrue(stdout().contains("delivered through #"), stdout());
    }

    @Test
    void fsckReportsOrphansAndExplainsWhyItDeletesNothing() throws IOException {
        // Write bytes straight into the blob store so there is something to report.
        registry.engine().blobs().put(
                new java.io.ByteArrayInputStream("stray".getBytes(StandardCharsets.UTF_8)));

        out.reset();
        assertEquals(0, run("fsck"), stderr());
        assertTrue(stdout().contains("1 orphaned artifact"), stdout());
        assertTrue(stdout().contains("reported, never deleted"), stdout());
    }

    @Test
    void deletingFromTheCommandLineWorksAndSaysSo() throws IOException {
        String digest = upload("disposable");
        run("publish", "fraud@1.0.0", digest);

        out.reset();
        assertEquals(0, run("rm", "fraud@1.0.0"), stderr());
        assertTrue(stdout().contains("deleted"), stdout());
        assertFalse(registry.engine().state()
                .version(io.cairn.core.Ref.of("fraud", "1.0.0")).orElseThrow().live());
    }

    @Test
    void aMissingFileForPutIsBadUsageRatherThanAServerError() {
        assertEquals(2, run("put", directory.resolve("nope.bin").toString()));
        assertTrue(stderr().contains("not a file"), stderr());
    }

    @Test
    void aBadLabelIsBadUsage() throws IOException {
        String digest = upload("the weights");
        err.reset();
        assertEquals(2, run("publish", "fraud@1.0.0", digest, "--label=novalue"));
        assertTrue(stderr().contains("key=value"), stderr());
    }
}
