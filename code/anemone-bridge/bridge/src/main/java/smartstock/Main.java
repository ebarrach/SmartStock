package smartstock;

import smartstock.anemone.*;
import smartstock.http.LocalServer;
import smartstock.state.EventHub;
import smartstock.state.EngineDiagnostics;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public final class Main {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path root = Paths.get(System.getProperty("smartstock.root", ".")).toAbsolutePath().normalize();
        int port = Integer.parseInt(System.getProperty("smartstock.port", "8080"));
        Map<String, EventHub> hubs = new LinkedHashMap<>();
        Map<String, EngineSession> sessions = new LinkedHashMap<>();
        CountDownLatch stop = new CountDownLatch(1);
        try {
            hubs.put("stock", new EventHub()); hubs.put("temperature", new EventHub());
            sessions.put("stock", new AnemoneSession(root.resolve("anemone-scenarios/connected"), hubs.get("stock")));
            sessions.put("temperature", new TemperatureEngineSession(root, hubs.get("temperature")));
            LocalServer server = new LocalServer(port, root.resolve("web"), sessions, hubs, stop::countDown, new EngineDiagnostics(root, sessions));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                stop.countDown(); server.close(); sessions.values().forEach(EngineSession::close);
            }, "smartstock-shutdown"));
            server.start();
            System.out.println("SMARTSTOCK Visualisation - Anemone - http://127.0.0.1:" + server.port());
            try { stop.await(); } finally { server.close(); }
        } finally { sessions.values().forEach(EngineSession::close); }
    }
}
