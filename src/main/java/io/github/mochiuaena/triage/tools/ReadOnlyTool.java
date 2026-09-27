package io.github.mochiuaena.triage.tools;

import io.github.mochiuaena.triage.domain.TriageModel.Evidence;
import java.util.List;

public interface ReadOnlyTool {
    String name();
    List<Evidence> execute(ToolContext context, String query);
}
