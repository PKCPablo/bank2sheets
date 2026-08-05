package bank2sheets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void noArgumentsPrintsUsageAndReturnsZero() {
        Result result = capture();
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Usage:"));
        assertTrue(result.stdout().contains("help"));
    }

    @Test
    void unknownCommandPrintsUsageAndReturnsNonZero() {
        Result result = capture("bogus");
        assertNotEquals(0, result.exitCode());
        assertTrue(result.stderr().contains("Unknown command: bogus"));
        assertTrue(result.stderr().contains("Usage:"));
    }

    /**
     * Runs Main.run while capturing System.out/System.err, then restores them.
     *
     * <p>ponytail: mirrors MainCommandsTest.capture; extract a shared test helper on next use.
     */
    private static Result capture(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            int exitCode = Main.run(args);
            return new Result(exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Result(int exitCode, String stdout, String stderr) {
    }
}
