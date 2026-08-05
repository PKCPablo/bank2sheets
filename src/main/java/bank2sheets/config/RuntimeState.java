package bank2sheets.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Runtime-only state persisted next to the repository (gitignored). Holds only
 * the one-time Salt Edge connection id written by the {@code connect} command —
 * never credentials or bank tokens.
 */
public final class RuntimeState {

    static final String CONNECTION_ID_KEY = "saltedge.connection_id";
    private final Path file;

    public RuntimeState(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** Reads the persisted connection id, or null when the file is absent or the key is missing. */
    public String connectionId() {
        if (!Files.exists(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            Properties props = new Properties();
            props.load(in);
            return props.getProperty(CONNECTION_ID_KEY);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read runtime state file " + file, e);
        }
    }

    public void saveConnectionId(String connectionId) {
        Properties props = new Properties();
        props.setProperty(CONNECTION_ID_KEY, connectionId);
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "bank2sheets runtime state (gitignored)");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write runtime state file " + file, e);
        }
    }
}
