package bank2sheets.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeStateTest {

    @TempDir
    Path tempDir;

    @Test
    void missingFileYieldsNullConnectionId() {
        assertNull(new RuntimeState(tempDir.resolve("none.properties")).read("saltedge"));
    }

    @Test
    void savesAndReadsConnectionId() {
        Path file = tempDir.resolve("state.properties");
        RuntimeState state = new RuntimeState(file);
        state.save("saltedge", "conn-1");
        assertEquals("conn-1", state.read("saltedge"));
    }

    @Test
    void savesBothProviderKeysWithoutClobbering() throws Exception {
        Path file = tempDir.resolve("state.properties");
        RuntimeState state = new RuntimeState(file);
        state.save("saltedge", "conn-1");
        state.save("enablebanking", "sess-1");

        assertEquals("conn-1", state.read("saltedge"));
        assertEquals("sess-1", state.read("enablebanking"));
        assertTrue(Files.readString(file).contains("saltedge.connection_id=conn-1"));
        assertTrue(Files.readString(file).contains("enablebanking.session_id=sess-1"));
    }

    @Test
    void missingFileOrKeyYieldsNullEnableBankingSessionId() {
        assertNull(new RuntimeState(tempDir.resolve("none.properties")).read("enablebanking"));

        Path file = tempDir.resolve("state.properties");
        RuntimeState state = new RuntimeState(file);
        state.save("saltedge", "conn-1");
        assertNull(state.read("enablebanking"));
    }
}
