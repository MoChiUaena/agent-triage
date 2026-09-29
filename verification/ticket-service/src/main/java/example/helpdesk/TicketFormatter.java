package example.helpdesk;

import java.util.Map;

final class TicketFormatter {
    private TicketFormatter() {}
    static Map<String, Object> view(String id, Map<?, ?> assignment) {
        return Map.of("id", id, "assigned", assignment != null && Boolean.TRUE.equals(assignment.get("assigned")));
    }
}
