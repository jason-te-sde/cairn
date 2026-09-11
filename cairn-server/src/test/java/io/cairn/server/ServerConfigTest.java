package io.cairn.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cairn.store.Durability;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Configuration, and the one rule that refuses to run. */
class ServerConfigTest {

    @TempDir
    Path directory;

    @Test
    void flagsOverrideTheConfigFile() throws IOException {
        Path file = directory.resolve("cairn.properties");
        Files.writeString(file, """
                data-dir=/from/file
                port=1234
                durability=NONE
                """);

        ServerConfig config = ServerConfig.parse(new String[] {
            "--config=" + file, "--port=9999"
        });

        assertEquals(Path.of("/from/file"), config.dataDir(), "the file supplied this");
        assertEquals(9999, config.port(), "and the flag overrode this");
        assertEquals(Durability.NONE, config.durability());
    }

    @Test
    void anUnknownFlagIsRefusedRatherThanIgnored() {
        // A typo in a flag that silently does nothing is how a node ends up running without the
        // setting somebody thought they had applied.
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.parse(new String[] {"--porrt=9999"}));
        assertTrue(refused.getMessage().contains("unknown flag"), refused.getMessage());
    }

    @Test
    void aNonLoopbackBindWithoutATokenIsRefused() {
        ServerConfig exposed = ServerConfig.parse(new String[] {"--address=0.0.0.0"});
        IllegalStateException refused = assertThrows(IllegalStateException.class, exposed::validate);
        assertTrue(refused.getMessage().contains("--token"), refused.getMessage());
        assertTrue(refused.getMessage().contains("remote code execution"),
                "the message should say why, not just that: " + refused.getMessage());
    }

    @Test
    void aTokenOrAnExplicitOverrideMakesItRun() {
        assertDoesNotThrow(() -> ServerConfig.parse(
                new String[] {"--address=0.0.0.0", "--token=s3cret"}).validate());
        assertDoesNotThrow(() -> ServerConfig.parse(
                new String[] {"--address=0.0.0.0", "--insecure"}).validate());
    }

    @Test
    void aLoopbackBindNeedsNothing() {
        assertDoesNotThrow(() -> ServerConfig.parse(new String[0]).validate());
        assertNull(ServerConfig.parse(new String[0]).token());
    }

    @Test
    void theAdminTokenMustDifferFromTheClientToken() {
        // Otherwise the separation is decoration: anybody who can publish can also eject whatever
        // is in production.
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> ServerConfig.parse(new String[] {"--token=same", "--admin-token=same"})
                        .validate());
        assertTrue(refused.getMessage().contains("not the same privilege"), refused.getMessage());
    }

    @Test
    void theDefaultsAreConservative() {
        ServerConfig defaults = ServerConfig.defaults(directory);
        assertEquals("127.0.0.1", defaults.address());
        assertEquals(Durability.SYNC_EACH, defaults.durability(),
                "a registry that does not fsync by default is a registry that loses a release");
        assertEquals(false, defaults.insecure());
    }

    @Test
    void theHelpTextCoversEveryFlag() {
        // So a flag cannot be added without being documented.
        String help = String.join("\n", ServerConfig.help());
        for (String flag : new String[] {
            "--config", "--data-dir", "--address", "--port", "--token", "--admin-token",
            "--insecure", "--durability", "--snapshot-every", "--effects-log",
            "--dispatch-every-millis"
        }) {
            assertTrue(help.contains(flag), flag + " is not in the help text");
        }
    }
}
