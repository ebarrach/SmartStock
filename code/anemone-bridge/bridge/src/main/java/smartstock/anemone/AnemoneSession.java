package smartstock.anemone;

import ab_blackboard.ABBlackboard;
import ab_data.*;
import ab_parser.AnimBachSimulParser;
import ab_scene.ABSetOfScenes;
import ab_simulators.ABSimulExec;
import scala.Tuple2;
import smartstock.state.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** All mutable Anemone state belongs exclusively to worker. No business rule is implemented here. */
public final class AnemoneSession implements EngineSession {
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "anemone-owner"));
    private final EventHub hub;
    private final ExecutionEvidence evidence = new ExecutionEvidence();
    private final String engineInstanceId = UUID.randomUUID().toString();
    private final String definitions, agentSource;
    private final AnimBachSimulParser parser = new AnimBachSimulParser();
    private ABBlackboard bb;
    private AB_AG ast;
    private ABSimulExec engine;
    private String sessionId, executionId, status;
    private int step, baseline;
    private boolean weightObserved, matrixObserved, running;
    private long sequence;
    private volatile Map<String, Object> latest;
    private final LinkedHashMap<String, String> commandIds = new LinkedHashMap<>();

    public AnemoneSession(Path scenarios, EventHub hub) throws Exception {
        this.hub = hub;
        definitions = new String(Files.readAllBytes(scenarios.resolve("definitions.anemone")), StandardCharsets.UTF_8);
        agentSource = new String(Files.readAllBytes(scenarios.resolve("withdrawal.anemone")), StandardCharsets.UTF_8).trim();
        worker.submit(() -> reset()).get();
    }
    public Map<String, Object> state() { return latest; }
    @Override public Map<String, Object> diagnostics() throws Exception {
        return worker.submit(() -> evidence.snapshot(parser, bb, engine, latest, "Stock", !worker.isShutdown())).get();
    }
    @Override public Map<String, Object> command(String name, String id, String argument) throws Exception {
        if ("seed".equals(name)) {
            if (id == null || argument == null || !argument.matches("[0-9]{1,4}") || Integer.parseInt(argument) > 1000)
                throw new IllegalArgumentException("Stock initial de 0 a 1000 requis");
            return worker.submit(() -> {
                String signature = "seed:" + argument;
                if (commandIds.containsKey(id)) {
                    if (!signature.equals(commandIds.get(id))) throw new IllegalArgumentException("Identifiant reutilise");
                    return latest;
                }
                reset(Integer.parseInt(argument)); commandIds.put(id, signature); return latest;
            }).get();
        }
        if (argument != null) throw new IllegalArgumentException("Aucun argument pour le stock");
        return command(name, id);
    }
    public Map<String, Object> command(String name, String id) throws Exception {
        return worker.submit(() -> {
            if (id == null || id.length() < 1 || id.length() > 128) throw new IllegalArgumentException("Identifiant de commande requis");
            if (commandIds.containsKey(id)) {
                if (!name.equals(commandIds.get(id))) throw new IllegalArgumentException("Identifiant réutilisé pour une autre commande");
                return latest;
            }
            switch (name) {
                case "initialize": publish("initialize", "Session déjà initialisée ; état conservé."); break;
                case "reset": reset(); break;
                case "weight": input(true); break;
                case "datamatrix": input(false); break;
                case "run":
                    if (!running) {
                        prepare(); running = true; status = "running";
                        publish("run", "Exécution du véritable agent Anemone.");
                        schedule(sessionId, executionId);
                    }
                    break;
                case "step":
                    if (running) throw new IllegalStateException("Exécution en cours");
                    prepare(); advance(); break;
                default: throw new IllegalArgumentException("Commande inconnue");
            }
            commandIds.put(id, name);
            while (commandIds.size() > 512) commandIds.remove(commandIds.keySet().iterator().next());
            return latest;
        }).get();
    }
    @SuppressWarnings("unchecked")
    private void reset() { reset(40); }
    private void reset(int initialQuantity) {
        running = false;
        bb = new ABBlackboard();
        scala.Tuple6<Object, Object, Object, Object, Object, Object> defs = parser.parse_prgm(definitions);
        bb.addDecls((scala.collection.immutable.List<AB_SET>) defs._1());
        bb.addEqns((scala.collection.immutable.List<AB_MAP_EQ>) defs._3());
        bb.tell(parser.parse_stinfo("stock(compresse," + initialQuantity + ")"));
        evidence.operation("ABBlackboard créé ; addDecls ; addEqns ; tell(stock(compresse," + initialQuantity + "))");
        sessionId = UUID.randomUUID().toString();
        newCycle();
        publish("reset", "Nouvelle session explicite : stock initial " + initialQuantity + ".");
    }
    private void newCycle() {
        ast = null; engine = null; step = 0; executionId = UUID.randomUUID().toString();
        status = "ready"; weightObserved = false; matrixObserved = false;
        baseline = bb.nb_occ(parser.parse_stinfo("retrait_confirme(compresse)"));
    }
    private void input(boolean weight) {
        if ("completed".equals(status)) newCycle();
        String term = weight ? "poids_change(compresse)" : "datamatrix_detecte(compresse)";
        bb.tell(parser.parse_stinfo(term));
        evidence.operation("ABBlackboard.tell(" + term + ")");
        if (weight) weightObserved = true; else matrixObserved = true;
        publish(weight ? "weight" : "datamatrix", "Événement publié : " + term);
    }
    private void prepare() {
        if ("error".equals(status)) throw new IllegalStateException("Session en erreur : RESET requis");
        if (ast == null || ast instanceof AB_AST_Empty_Agent) {
            if ("completed".equals(status)) newCycle();
            ast = parser.parse_agent(agentSource);
            engine = new ABSimulExec(ast, bb, new ABSetOfScenes());
            evidence.operation("AnimBachSimulParser.parse_agent ; ABSimulExec instancié");
        }
    }
    private void schedule(String session, String execution) {
        worker.schedule(() -> {
            if (!running || !sessionId.equals(session) || !executionId.equals(execution)) return;
            advance();
            if (running) schedule(session, execution);
        }, 220, TimeUnit.MILLISECONDS);
    }
    private void advance() {
        try {
            if (step >= 256) throw new IllegalStateException("Limite de pas atteinte");
            evidence.beforeRun(ast, sessionId, executionId);
            Tuple2<Object, AB_AG> result = engine.run_one(ast);
            ast = result._2(); step++;
            boolean success = (Boolean) result._1();
            evidence.afterRun(success, ast);
            if (!success) { status = "blocked"; running = false; }
            else if (ast instanceof AB_AST_Empty_Agent) { status = "completed"; running = false; }
            else status = running ? "running" : "paused";
            publish("step", "Pas " + step + " · succès=" + success + (success ? "" : " · continuation conservée, RUN pour reprendre."));
        } catch (Throwable e) {
            running = false; status = "error";
            publish("error", e.getClass().getSimpleName() + " : " + String.valueOf(e.getMessage()));
        }
    }
    private void publish(String kind, String message) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("source", "anemone"); s.put("engineInstanceId", engineInstanceId); s.put("sessionId", sessionId); s.put("sequence", ++sequence);
        s.put("timestamp", Instant.now().toString()); s.put("eventType", kind); s.put("message", message);
        List<Map<String, Object>> terms = new ArrayList<>();
        Integer stock = null; int stockTerms = 0; boolean numericStock = true;
        scala.collection.Iterator<Tuple2<AB_SI_ELM, Object>> it = bb.storeContents().iterator();
        while (it.hasNext()) {
            Tuple2<AB_SI_ELM, Object> entry = it.next(); AB_SI_ELM term = entry._1();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("term", term.ab_toString()); row.put("multiplicity", entry._2()); terms.add(row);
            if (term instanceof AB_SI_COMPOSED) {
                AB_SI_COMPOSED c = (AB_SI_COMPOSED) term;
                if (c.functor().equals("stock") && c.functorArgs().size() == 2 && c.functorArgs().apply(0).ab_toString().equals("compresse")) {
                    stockTerms += ((Number) entry._2()).intValue();
                    try { stock = Integer.valueOf(c.functorArgs().apply(1).ab_toString()); }
                    catch (NumberFormatException e) { numericStock = false; }
                }
            }
        }
        terms.sort(Comparator.comparing(row -> row.get("term").toString()));
        s.put("stock", stockTerms == 1 && numericStock ? stock : null);
        s.put("stockStatus", stockTerms == 0 ? "transient-absent" : stockTerms == 1 && numericStock ? "valid" : "inconsistent");
        s.put("weightPresent", bb.ask(parser.parse_stinfo("poids_change(compresse)")));
        s.put("dataMatrixPresent", bb.ask(parser.parse_stinfo("datamatrix_detecte(compresse)")));
        int count = bb.nb_occ(parser.parse_stinfo("retrait_confirme(compresse)"));
        s.put("confirmationMultiplicity", count);
        Map<String, Object> observations = new LinkedHashMap<>();
        observations.put("weightDetected", weightObserved); observations.put("dataMatrixDetected", matrixObserved);
        observations.put("withdrawalConfirmed", count > baseline);
        s.put("observations", observations); s.put("blackboard", terms);
        s.put("executionId", executionId); s.put("executionStatus", status); s.put("executionStep", step);
        s.put("remainingAgent", ast == null ? agentSource : ast instanceof AB_AST_Empty_Agent ? "" : ast.ab_toString());
        latest = Collections.unmodifiableMap(s);
        hub.publish(new EventHub.Event(sequence, Json.encode(s)));
    }
    @Override public void close() { worker.shutdownNow(); }
}
