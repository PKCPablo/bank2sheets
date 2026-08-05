package bank2sheets.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeStateTest {

    @TempDir
    Path tempDir;

    @Test
    void missingFileYieldsNullConnectionId() {
        assertNull(new RuntimeState(tempDir.resolve("none.properties")).connectionId());
    }

    @Test
    void savesAndReadsConnectionId() {
        Path file = tempDir.resolve("state.properties");
        RuntimeState state = new RuntimeState(file);
        state.saveConnectionId("conn-1");
        assertEquals("conn-1", state.connectionId());
    }
}
