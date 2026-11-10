package bank2sheets;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.IntSupplier;

/** Captures System.out/System.err for CLI tests. */
final class CliTestSupport {

    private CliTestSupport() {
    }

    /** Runs the action while capturing stdout/stderr, then restores the original streams. */
    static Result capture(IntSupplier action) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            int exitCode = action.getAsInt();
            return new Result(exitCode, stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    record Result(int exitCode, String stdout, String stderr) {
    }
}
