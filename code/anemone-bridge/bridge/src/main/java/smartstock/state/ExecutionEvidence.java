package smartstock.state;

import ab_blackboard.ABBlackboard;
import ab_data.AB_AG;
import ab_parser.AnimBachSimulParser;
import ab_simulators.ABSimulExec;
import java.time.Instant;
import java.util.*;

/** Observations only. Accessed on the session owner thread; never changes an Anemone object. */
public final class ExecutionEvidence {
    private long calls;
    private String lastOperation;
    private String lastOperationAt;
    private Map<String, Object> lastRun;

    public void operation(String name) { lastOperation = name; lastOperationAt = Instant.now().toString(); }
    public void beforeRun(AB_AG ast, String sessionId, String executionId) {
        calls++;
        lastRun = new LinkedHashMap<>();
        lastRun.put("callNumber", calls); lastRun.put("sessionId", sessionId); lastRun.put("executionId", executionId);
        lastRun.put("invokedAt", Instant.now().toString()); lastRun.put("thread", Thread.currentThread().getName());
        lastRun.put("agentBefore", ast.ab_toString()); lastRun.put("returned", false);
        operation("ab_simulators.ABSimulExec.run_one");
    }
    public void afterRun(boolean success, AB_AG remaining) {
        lastRun.put("returned", true); lastRun.put("success", success); lastRun.put("agentAfter", remaining.ab_toString());
        lastRun.put("returnedAt", Instant.now().toString());
    }
    public Map<String, Object> snapshot(AnimBachSimulParser parser, ABBlackboard bb, ABSimulExec engine,
                                      Map<String, Object> state, String setName, boolean active) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sampledAt", Instant.now().toString()); out.put("active", active);
        out.put("ownerThread", Thread.currentThread().getName());
        out.put("parserRuntimeClass", parser.getClass().getName());
        out.put("blackboardRuntimeClass", bb.getClass().getName());
        out.put("blackboardIdentityHash", Integer.toHexString(System.identityHashCode(bb)));
        out.put("executionCreated", engine != null); out.put("executionRuntimeClass", engine == null ? null : engine.getClass().getName());
        out.put("loadedEquationCount", bb.listEqns().size()); out.put("setName", setName);
        List<String> members = new ArrayList<>();
        scala.collection.Iterator<String> iterator = bb.setMembers(setName).iterator();
        while (iterator.hasNext()) members.add(iterator.next());
        out.put("loadedSetMembers", members); out.put("loadedSetMemberCount", members.size());
        out.put("runOneCallsSinceProcessStart", calls);
        out.put("lastOperation", lastOperation); out.put("lastOperationAt", lastOperationAt);
        out.put("lastRunOne", lastRun == null ? null : new LinkedHashMap<>(lastRun));
        out.put("state", state);
        return out;
    }
}
