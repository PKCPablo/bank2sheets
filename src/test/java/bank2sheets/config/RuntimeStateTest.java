package bank2sheets.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeStateTest {

    @TempDir
    Path tempDir;

    @Test
    void missingFileYieldsNullSessionId() {
        assertNull(new RuntimeState(tempDir.resolve("none.properties")).read("enablebanking"));
    }

    @Test
    void savesAndReadsSessionId() {
        Path file = tempDir.resolve("state.properties");
        RuntimeState state = new RuntimeState(file);
        state.save("enablebanking", "sess-1");
        assertEquals("sess-1", state.read("enablebanking"));
    }

    @Test
    void unknownProviderFails() {
        RuntimeState state = new RuntimeState(tempDir.resolve("state.properties"));
        assertThrows(IllegalArgumentException.class, () -> state.read("saltedge"));
        assertThrows(IllegalArgumentException.class, () -> state.save("saltedge", "conn-1"));
    }

    @Test
    void legacySaltEdgeKeyIsPreservedAndIgnored() throws Exception {
        Path file = tempDir.resolve("state.properties");
        Files.writeString(file, "saltedge.connection_id=conn-1\nenablebanking.session_id=sess-1\n");
        RuntimeState state = new RuntimeState(file);

        assertEquals("sess-1", state.read("enablebanking"));

        state.save("enablebanking", "sess-2");

        assertEquals("sess-2", state.read("enablebanking"));
        assertTrue(Files.readString(file).contains("saltedge.connection_id=conn-1"));
    }
}
