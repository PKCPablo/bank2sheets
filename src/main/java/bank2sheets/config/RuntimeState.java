package bank2sheets.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Runtime-only state persisted next to the repository (gitignored). Holds the
 * Enable Banking session id written by the {@code connect} command — never
 * credentials or bank tokens. Legacy keys from removed providers are preserved
 * on save but never read.
 */
public final class RuntimeState {

    private static final String ENABLE_BANKING_SESSION_ID_KEY = "enablebanking.session_id";

    private final Path file;

    public RuntimeState(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** Reads the persisted Enable Banking session id (null when absent). */
    public String read(String provider) {
        return load().getProperty(key(provider));
    }

    /** Saves the session id, preserving any other keys already in the file. */
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
        if ("enablebanking".equals(provider)) {
            return ENABLE_BANKING_SESSION_ID_KEY;
        }
        throw new IllegalArgumentException("Unknown provider: " + provider);
    }
}
