package smartstock.state;

import smartstock.anemone.EngineSession;
import ab_blackboard.ABBlackboard;
import ab_parser.AnimBachSimulParser;
import ab_simulators.ABSimulExec;
import java.awt.GraphicsEnvironment;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.security.CodeSource;
import java.time.Instant;
import java.util.*;

/** Reports configuration separately from actual JVM/class/session observations. */
public final class EngineDiagnostics {
    private final Map<String, EngineSession> sessions;
    private final Map<String, Object> configuration = new LinkedHashMap<>();
    public EngineDiagnostics(Path root, Map<String, EngineSession> sessions) throws Exception {
        this.sessions = sessions;
        Properties properties = new Properties();
        try (java.io.InputStream in = Files.newInputStream(root.resolve("bridge/bridge.properties"))) { properties.load(in); }
        configuration.put("propertiesFile", root.resolve("bridge/bridge.properties").toString());
        configuration.put("jarPathInPropertiesFile", properties.getProperty("anemone.jar"));
        configuration.put("note", "Configuration du fichier seulement. Le classpath peut avoir été choisi par -AnemoneJar, ANEMONE_JAR ou IntelliJ. L'origine runtime ci-dessous fait foi.");
    }
    private Map<String, Object> classInfo(Class<?> type) {
        Map<String, Object> info = new LinkedHashMap<>(); info.put("className", type.getName());
        CodeSource source = type.getProtectionDomain().getCodeSource();
        info.put("codeSource", source == null ? null : source.getLocation().toExternalForm());
        String file = null;
        try { if (source != null && source.getLocation().getProtocol().equals("file")) file = Paths.get(source.getLocation().toURI()).getFileName().toString(); }
        catch (Exception ignored) { /* Unknown source stays explicitly null. */ }
        info.put("archiveName", file);
        java.net.URL resource = type.getResource("/" + type.getName().replace('.', '/') + ".class");
        info.put("classResource", resource == null ? null : resource.toExternalForm());
        info.put("classLoader", type.getClassLoader() == null ? "bootstrap" : type.getClassLoader().getClass().getName());
        return info;
    }
    public Map<String, Object> snapshot() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>(), runtime = new LinkedHashMap<>(), reports = new LinkedHashMap<>();
        result.put("source", "bridge-runtime"); result.put("sampledAt", Instant.now().toString()); result.put("configuration", configuration);
        runtime.put("javaVersion", System.getProperty("java.version")); runtime.put("javaVendor", System.getProperty("java.vendor"));
        runtime.put("vmName", System.getProperty("java.vm.name")); runtime.put("headlessProperty", System.getProperty("java.awt.headless"));
        runtime.put("headless", GraphicsEnvironment.isHeadless()); runtime.put("uptimeMillis", ManagementFactory.getRuntimeMXBean().getUptime());
        runtime.put("loadedClasses", Arrays.asList(classInfo(AnimBachSimulParser.class), classInfo(ABBlackboard.class), classInfo(ABSimulExec.class)));
        result.put("runtime", runtime);
        for (Map.Entry<String, EngineSession> session : sessions.entrySet()) reports.put(session.getKey(), session.getValue().diagnostics());
        result.put("sessions", reports);
        result.put("scope", "Observations de ce processus. Les deux sessions sont échantillonnées successivement, sans transaction globale. Aucun appel moteur n'est déclenché par ce diagnostic. Les compteurs incluent les appels run_one retournant false ; ils survivent à RESET mais pas à un arrêt JVM.");
        return result;
    }
}
