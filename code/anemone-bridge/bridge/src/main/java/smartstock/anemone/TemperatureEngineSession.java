package smartstock.anemone;

import ab_blackboard.ABBlackboard;
import ab_data.*;
import ab_parser.AnimBachSimulParser;
import ab_scene.ABSetOfScenes;
import ab_simulators.ABSimulExec;
import scala.Tuple2;
import smartstock.state.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** No temperature classification in Java: fan(q) is resolved by Anemone equations. */
public final class TemperatureEngineSession implements EngineSession {
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "temperature-owner"));
    private final EventHub hub;
    private final ExecutionEvidence evidence = new ExecutionEvidence();
    private final AnimBachSimulParser parser = new AnimBachSimulParser();
    private final String definitions, agent, engineId = UUID.randomUUID().toString();
    private final int min, max, initial;
    private final Map<String, String> commands = new LinkedHashMap<>();
    private ABBlackboard bb;
    private AB_AG ast;
    private ABSimulExec engine;
    private String sessionId, executionId, status;
    private int measured, step;
    private int previousPower;
    private long sequence;
    private boolean running;
    private volatile Map<String, Object> latest;

    public TemperatureEngineSession(Path root, EventHub hub) throws Exception {
        this.hub = hub;
        Path dir = root.resolve("anemone-scenarios/temperature");
        Properties metadata = new Properties();
        try (java.io.InputStream in = Files.newInputStream(dir.resolve("metadata.properties"))) { metadata.load(in); }
        byte[] policyBytes = Files.readAllBytes(root.resolve("config/temperature-policy.json"));
        byte[] definitionBytes = Files.readAllBytes(dir.resolve("definitions.anemone"));
        if (!hash(policyBytes).equals(metadata.getProperty("policySha256")) || !hash(definitionBytes).equals(metadata.getProperty("definitionsSha256")))
            throw new IllegalStateException("Régénérer le scénario température : node experiments/generate-temperature-scenario.mjs");
        definitions = new String(definitionBytes, StandardCharsets.UTF_8);
        agent = new String(Files.readAllBytes(dir.resolve("regulation.anemone")), StandardCharsets.UTF_8).trim();
        min = Integer.parseInt(metadata.getProperty("minTenths")); max = Integer.parseInt(metadata.getProperty("maxTenths")); initial = Integer.parseInt(metadata.getProperty("initialTenths"));
        try { worker.submit(() -> reset()).get(); }
        catch (Exception e) { worker.shutdownNow(); throw e; }
    }
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format("%02x", b & 255));
        return out.toString();
    }
    @Override public Map<String, Object> state() { return latest; }
    @Override public Map<String, Object> diagnostics() throws Exception {
        return worker.submit(() -> evidence.snapshot(parser, bb, engine, latest, "Temperatures", !worker.isShutdown())).get();
    }
    @Override public Map<String, Object> command(String name, String id, String argument) throws Exception {
        return worker.submit(() -> {
            if (id == null || id.isEmpty() || id.length() > 128) throw new IllegalArgumentException("Identifiant requis");
            String signature = name + ":" + argument;
            if (commands.containsKey(id)) {
                if (!signature.equals(commands.get(id))) throw new IllegalArgumentException("Identifiant réutilisé");
                return latest;
            }
            switch (name) {
                case "initialize": publish("initialize", "Session température déjà initialisée."); break;
                case "reset": reset(); break;
                case "apply":
                    if (running || "paused".equals(status)) throw new IllegalStateException("Terminer la régulation ou RESET avant une nouvelle mesure");
                    if ("error".equals(status)) throw new IllegalStateException("RESET requis");
                    int value = parseTemperature(argument);
                    // Replace old telemetry/output, not the decision. No new ventilation term is inserted here.
                    Object lastPower = latest.get("fanPower");
                    if (lastPower instanceof Number) previousPower = ((Number)lastPower).intValue();
                    List<AB_SI_ELM> old = new ArrayList<>();
                    scala.collection.Iterator<AB_SI_ELM> keys = bb.storeContents().keysIterator();
                    while (keys.hasNext()) old.add(keys.next());
                    for (AB_SI_ELM term : old) bb.getall(term);
                    measured = value; ast = null; engine = null; step = 0; status = "ready"; executionId = UUID.randomUUID().toString();
                    bb.tell(parser.parse_stinfo("temperature(t" + measured + ")"));
                    bb.tell(parser.parse_stinfo("previous(p" + previousPower + ")"));
                    evidence.operation("ABBlackboard.getall anciens termes ; tell(temperature(t" + measured + "))");
                    publish("temperature", "Mesure transmise : " + new BigDecimal(measured).movePointLeft(1) + " °C (simulée)."); break;
                case "run":
                    if (!running) { prepare(); running = true; status = "running"; publish("run", "Résolution par le véritable moteur Anemone."); schedule(sessionId, executionId); }
                    break;
                case "step":
                    if (running) throw new IllegalStateException("Exécution en cours");
                    prepare(); advance(); break;
                default: throw new IllegalArgumentException("Commande inconnue");
            }
            commands.put(id, signature);
            while (commands.size() > 512) commands.remove(commands.keySet().iterator().next());
            return latest;
        }).get();
    }
    private int parseTemperature(String text) {
        if (text == null || !text.matches("[0-9]{1,3}(\\.[0-9])?")) throw new IllegalArgumentException("Température requise au dixième de degré");
        int value = new BigDecimal(text).movePointRight(1).intValueExact();
        if (value < min || value > max) throw new IllegalArgumentException("Température hors de la plage de démonstration");
        return value;
    }
    @SuppressWarnings("unchecked") private void reset() {
        running = false; bb = new ABBlackboard();
        scala.Tuple6<Object, Object, Object, Object, Object, Object> d = parser.parse_prgm(definitions);
        bb.addDecls((scala.collection.immutable.List<AB_SET>)d._1());
        bb.addEqns((scala.collection.immutable.List<AB_MAP_EQ>)d._3());
        previousPower = 0; bb.tell(parser.parse_stinfo("previous(p0)"));
        measured = initial; bb.tell(parser.parse_stinfo("temperature(t" + measured + ")"));
        evidence.operation("ABBlackboard créé ; addDecls ; addEqns ; tell(temperature(t" + measured + "))");
        ast = null; engine = null; step = 0; status = "ready";
        sessionId = UUID.randomUUID().toString(); executionId = UUID.randomUUID().toString();
        publish("reset", "Nouvelle session température · seuils de démonstration.");
    }
    private void prepare() {
        if ("error".equals(status)) throw new IllegalStateException("RESET requis");
        if ("completed".equals(status)) throw new IllegalStateException("Appliquer une nouvelle température avant RUN");
        if (ast == null) { ast = parser.parse_agent(agent); engine = new ABSimulExec(ast, bb, new ABSetOfScenes()); evidence.operation("AnimBachSimulParser.parse_agent ; ABSimulExec instancié"); }
    }
    private void schedule(String session, String execution) {
        worker.schedule(() -> {
            if (!running || !sessionId.equals(session) || !executionId.equals(execution)) return;
            advance(); if (running) schedule(session, execution);
        }, 260, TimeUnit.MILLISECONDS);
    }
    private void advance() {
        try {
            if (step >= 256) throw new IllegalStateException("Limite de pas atteinte");
            evidence.beforeRun(ast, sessionId, executionId);
            Tuple2<Object, AB_AG> result = engine.run_one(ast); ast = result._2(); step++;
            boolean ok = (Boolean)result._1();
            evidence.afterRun(ok, ast);
            if (!ok) { running = false; status = "blocked"; }
            else if (ast instanceof AB_AST_Empty_Agent) { running = false; status = "completed"; }
            else status = running ? "running" : "paused";
            publish("step", "Pas Anemone " + step + " · succès=" + ok);
        } catch (Throwable e) { running = false; status = "error"; publish("error", e.toString()); }
    }
    private void publish(String kind, String message) {
        Map<String, Object> s = new LinkedHashMap<>();
        List<Map<String, Object>> terms = new ArrayList<>(); Integer fan = null; boolean inputPresent = false;
        scala.collection.Iterator<Tuple2<AB_SI_ELM, Object>> it = bb.storeContents().iterator();
        while (it.hasNext()) {
            Tuple2<AB_SI_ELM, Object> e = it.next(); Map<String, Object> row = new LinkedHashMap<>();
            row.put("term", e._1().ab_toString()); row.put("multiplicity", e._2()); terms.add(row);
            if (e._1() instanceof AB_SI_COMPOSED) {
                AB_SI_COMPOSED c = (AB_SI_COMPOSED)e._1();
                if (c.functor().equals("temperature")) inputPresent = true;
                if (c.functor().equals("ventilation") && c.functorArgs().size() == 1) {
                    try { fan = Integer.valueOf(c.functorArgs().head().ab_toString()); } catch (NumberFormatException ignored) {}
                }
            }
        }
        terms.sort(Comparator.comparing(row -> row.get("term").toString()));
        s.put("source", "anemone"); s.put("demoId", "temperature"); s.put("engineInstanceId", engineId);
        s.put("sessionId", sessionId); s.put("sequence", ++sequence); s.put("executionId", executionId);
        s.put("timestamp", Instant.now().toString()); s.put("eventType", kind); s.put("message", message);
        s.put("temperature", new BigDecimal(measured).movePointLeft(1)); s.put("temperaturePresent", inputPresent);
        s.put("fanPower", fan); s.put("executionStatus", status); s.put("executionStep", step); s.put("blackboard", terms);
        s.put("remainingAgent", ast == null ? agent : ast instanceof AB_AST_Empty_Agent ? "" : ast.ab_toString());
        Map<String, Object> obs = new LinkedHashMap<>(); obs.put("temperatureApplied", true); obs.put("decisionReceived", fan != null);
        s.put("observations", obs);
        latest = Collections.unmodifiableMap(s); hub.publish(new EventHub.Event(sequence, Json.encode(s)));
    }
    @Override public void close() { worker.shutdownNow(); }
}
