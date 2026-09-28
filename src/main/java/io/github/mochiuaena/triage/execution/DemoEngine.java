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
        if (!QuestionScope.supports(session.question(), session.context())) {
            return new Decision(Status.INSUFFICIENT_EVIDENCE,
                new Diagnosis(List.of(), List.of(), List.of("请询问 " + session.context().service() + " 的请求延迟或下游超时。"),
                    "当前只覆盖请求延迟和下游超时，该问题没有可用证据。"));
        }
        for (String tool : session.toolNames()) session.callTool(tool, session.question());
        Diagnosis diagnosis = reasoner.diagnose(session.evidence(), session.context().serviceInfo());
        return new Decision(diagnosis.possibleCauses().isEmpty() ? Status.INSUFFICIENT_EVIDENCE : Status.SUCCEEDED, diagnosis);
    }
}
