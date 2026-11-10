---
status: completed
created: 2026-11-09
task: 1/1
required_gates:
  architect_review: false
  memory_update: true
---

# Extract shared CLI test capture helper

**Goal:** Remove the duplicated `System.out`/`System.err` capture logic and `Result` record from `MainTest` and `MainCommandsTest` by extracting a single package-private test helper.

**Architecture:** Add `CliTestSupport` under `src/test/java/bank2sheets/` exposing `static Result capture(IntSupplier action)` plus the nested `Result` record. Both CLI test classes delegate to it; no production code, CLI contracts, or test behavior changes.

**Tech Stack:** Java 21, JUnit 5, Maven wrapper (`.\scripts\mvn.ps1`).

**Scope classification:** `single-task`

## Context

The only actionable item from the ponytail debt scan is the deliberate duplication marker:

- `src/test/java/bank2sheets/MainTest.java:34` — `ponytail: mirrors MainCommandsTest.capture; extract a shared test helper on next use.`
- `src/test/java/bank2sheets/MainCommandsTest.java:301` — `ponytail: mirrors MainTest.capture; extract a shared test helper on next use.`

Both methods do the same thing: swap `System.out`/`System.err`, run an `IntSupplier`, restore the streams, and return a `(exitCode, stdout, stderr)` record.

The remaining `ponytail:` markers are **not** actionable and stay untouched:

- `SheetsException.java:7` — explicitly deferred until a third client appears; consolidating two exception types now would add a base class without reducing behavior.
- `FakeHttp.java:15` — records why the shared fake stays in its historical package (moving it would churn every test file).
- `SheetsTestSupport.java:20` — records why the throwaway RSA key is a static fixture.
- `pom.xml:18` — justification for the single Gson dependency, not debt.

## Task 1: Extract `CliTestSupport.capture`

**Objective:** `MainTest` and `MainCommandsTest` share one capture implementation, both `ponytail:` markers are gone, and the suite stays green.

**Files:**
- Create: `src/test/java/bank2sheets/CliTestSupport.java`
- Modify: `src/test/java/bank2sheets/MainTest.java`
- Modify: `src/test/java/bank2sheets/MainCommandsTest.java`
- Validate: `src/test/java/bank2sheets/MainTest.java`, `src/test/java/bank2sheets/MainCommandsTest.java`

**Step 1: Establish expected behaviour**

Before editing, record the baseline:

- `.\scripts\mvn.ps1 test '-Dtest=MainTest,MainCommandsTest'` → all tests green.
- `MainTest.capture(String...)` and `MainCommandsTest.capture(IntSupplier)` contain byte-for-byte equivalent stream-swapping logic (plus the local `Result` records).

After the change, every existing test must assert exactly the same thing; only the location of the helper changes.

**Step 2: Add or update validation**

No new tests are needed — the existing `MainTest` and `MainCommandsTest` suite is the validation. Do not add a test-only dependency or a production seam.

**Step 3: Write minimal implementation**

Create `CliTestSupport` as a package-private final class in `bank2sheets` (`src/test/java/bank2sheets/CliTestSupport.java`):

```java
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
```

Then update the two tests:

- `MainTest`:
  - Add `import bank2sheets.CliTestSupport.Result;` (same-package nested-type import is legal) or use `CliTestSupport.Result` explicitly — either is fine.
  - Keep the local `capture(String... args)` convenience but reduce its body to `return CliTestSupport.capture(() -> Main.run(args));`.
  - Delete the local stream-swapping logic, the `Result` record, and now-unused imports (`ByteArrayOutputStream`, `PrintStream`, `StandardCharsets`).
  - Delete the `ponytail:` Javadoc paragraph; keep the short method Javadoc if it still adds value.
- `MainCommandsTest`:
  - Add the same `Result` import.
  - Delete its private `capture(IntSupplier)` and `Result` record, and call `CliTestSupport.capture(...)` at the one call site (`runCli`).
  - Delete the `ponytail:` Javadoc paragraph and any imports that become unused (`ByteArrayOutputStream`, `PrintStream`, `StandardCharsets`).

Constraints:
- Do not change assertions, test names, fixture data, or CLI behavior.
- Do not touch production code, `pom.xml`, or the other `ponytail:` markers.
- Keep the 4-space indentation and LF line endings from `.editorconfig`.

**Step 4: Run test to verify pass**

Run from the repository root:

1. (agent) `.\scripts\mvn.ps1 test '-Dtest=MainTest,MainCommandsTest'`
   Expected: all `MainTest` and `MainCommandsTest` tests pass, `BUILD SUCCESS`.
2. (agent) `.\scripts\mvn.ps1 test`
   Expected: 44 tests, 0 failures/errors, JaCoCo 80 % LINE gate met, `BUILD SUCCESS`.
3. (agent) `git diff --check` and `git status --short`
   Expected: only the three test files above changed; no whitespace errors; no secrets in output.
4. (agent) Search the test tree for the removed markers and duplicate logic:
   `Select-String -Path src/test/java/bank2sheets/*.java -Pattern 'mirrors MainTest.capture|mirrors MainCommandsTest.capture|ByteArrayOutputStream'`
   Expected: no matches in `MainTest.java` / `MainCommandsTest.java`; only `CliTestSupport.java` (and unrelated matches if any).

**Step 5: Commit**

Only if the user explicitly asks for a commit.

`git add src/test/java/bank2sheets/CliTestSupport.java src/test/java/bank2sheets/MainTest.java src/test/java/bank2sheets/MainCommandsTest.java docs/ai/plans/2026-11-09_172145-extract-cli-test-capture-helper.md`
`git commit -m "test: extract shared CLI capture helper"`

## Memory update

After the task passes, append a short 2026-11 entry to `docs/ai/logs/2026-11.md` recording the extracted helper, the removed `ponytail:` markers, and the validation results. Do not rewrite historical logs.
