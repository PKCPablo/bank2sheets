package bank2sheets.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Runtime-only state persisted next to the repository (gitignored). Holds the
 * one-time Salt Edge connection id and the Enable Banking session id written by
 * the {@code connect} command — never credentials or bank tokens. Each
 * provider's key is read/written independently so saving one never clobbers the
 * other.
 */
public final class RuntimeState {

    public static final String SALTEDGE_CONNECTION_ID_KEY = "saltedge.connection_id";
    public static final String ENABLE_BANKING_SESSION_ID_KEY = "enablebanking.session_id";

    private final Path file;

    public RuntimeState(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** Reads the persisted connection id, or null when the file is absent or the key is missing. */
    public String connectionId() {
        return read("saltedge");
    }

    public void saveConnectionId(String connectionId) {
        save("saltedge", connectionId);
    }

    /** Reads the provider's persisted id (Salt Edge connection id / Enable Banking session id). */
    public String read(String provider) {
        if (!Files.exists(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            Properties props = new Properties();
            props.load(in);
            return props.getProperty(key(provider));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read runtime state file " + file, e);
        }
    }

    /** Saves the provider's id, preserving the other provider's key in the same file. */
    public void save(String provider, String id) {
        Properties props = load();
        props.setProperty(key(provider), id);
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(out, "bank2sheets runtime state (gitignored)");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write runtime state file " + file, e);
        }
    }

    private Properties load() {
        Properties props = new Properties();
        if (!Files.exists(file)) {
            return props;
        }
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read runtime state file " + file, e);
        }
        return props;
    }

    private static String key(String provider) {
        return switch (provider) {
            case "saltedge" -> SALTEDGE_CONNECTION_ID_KEY;
            case "enablebanking" -> ENABLE_BANKING_SESSION_ID_KEY;
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        };
    }
}
