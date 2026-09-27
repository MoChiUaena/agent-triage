package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class DemoEngine implements TriageEngine {
    private final DemoReasoner reasoner;

    public DemoEngine(DemoReasoner reasoner) { this.reasoner = reasoner; }
    @Override public String mode() { return "DEMO"; }

    @Override public Decision investigate(ExecutionSession session) {
        if (!reasoner.supports(session.question())) {
            return new Decision(Status.INSUFFICIENT_EVIDENCE,
                new Diagnosis(List.of(), List.of(), List.of("请询问 order-service 的订单延迟、健康状态或下游超时。"),
                    "当前演示只覆盖订单查询和库存下游超时，该问题没有可用证据。"));
        }
        for (String tool : session.toolNames()) session.callTool(tool, session.question());
        Diagnosis diagnosis = reasoner.diagnose(session.evidence());
        return new Decision(diagnosis.possibleCauses().isEmpty() ? Status.INSUFFICIENT_EVIDENCE : Status.SUCCEEDED, diagnosis);
    }
}
