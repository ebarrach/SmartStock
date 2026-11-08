package smartstock.anemone;

import java.util.Map;

/** One independently serialized, persistent demonstration. */
public interface EngineSession extends AutoCloseable {
    Map<String, Object> state();
    Map<String, Object> diagnostics() throws Exception;
    Map<String, Object> command(String name, String id, String argument) throws Exception;
    @Override void close();
}
