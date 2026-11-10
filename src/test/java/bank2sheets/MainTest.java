package bank2sheets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import bank2sheets.CliTestSupport.Result;

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

    /** Runs Main.run while capturing System.out/System.err, then restores them. */
    private static Result capture(String... args) {
        return CliTestSupport.capture(() -> Main.run(args));
    }
}
