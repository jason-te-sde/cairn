package io.cairn.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * One jar, two programs, and which one an invocation means.
 *
 * <p>This file exists because the dispatch shipped wrong and nothing tested it. The rule was "is
 * the first argument a bare word", so {@code cairnctl --url=http://host:9080 status} — the
 * invocation the client's own help documents — went to the server, which refused {@code --url} as
 * an unknown flag. The clean-clone CI job was the only thing that ran the client with a leading
 * flag, and it was the thing that found it.
 */
class MainDispatchTest {

    @ParameterizedTest(name = "[{index}] {0} -> client={1}")
    @CsvSource(quoteCharacter = '\'', value = {
        // Server: flags only, in any combination.
        "'', false",
        "'--port=9080', false",
        "'--data-dir=/var/lib/cairn|--address=0.0.0.0|--token=s3cret', false",
        "'--insecure', false",
        "'--help', false",
        "'-h', false",
        "'--config=/etc/cairn/cairn.properties', false",

        // Client: a bare word anywhere.
        "'status', true",
        "'help', true",
        "'publish|fraud@1.0.0|sha256:abc', true",
        "'--json|status', true",
        "'--url=http://127.0.0.1:9081|status', true",
        "'--token=s3cret|--url=http://host:9080|ls|fraud', true",
        "'--json|--url=http://host/|verify', true",
    })
    void theRightProgramIsChosen(String joined, boolean client) {
        String[] args = joined.isEmpty() ? new String[0] : joined.split("\\|");
        if (client) {
            assertTrue(Main.looksLikeAClientCommand(args),
                    joined + " should be a client command");
        } else {
            assertFalse(Main.looksLikeAClientCommand(args),
                    joined + " should start the server");
        }
    }

    @Test
    void everyServerFlagIsAFlag() {
        // The rule only works because no server flag is a bare word. Asserted against the help
        // text, so adding a positional server argument fails here rather than silently routing
        // every invocation that uses it to the client.
        for (String line : ServerConfig.help()) {
            String trimmed = line.trim();
            assertTrue(trimmed.startsWith("--"),
                    "server flags must all begin with '--', but the help lists: " + trimmed);
        }
    }
}
