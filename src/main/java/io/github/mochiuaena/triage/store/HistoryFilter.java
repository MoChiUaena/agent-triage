package io.github.mochiuaena.triage.store;

import io.github.mochiuaena.triage.domain.TriageModel.Status;
import java.time.Instant;
import java.util.*;

public record HistoryFilter(String query, String service, Status status, String mode, Instant from, Instant until) {
    public HistoryFilter {
        query = blank(query); service = blank(service); mode = blank(mode);
        if (query != null && query.length() > 200 || service != null && !service.matches("[a-z][a-z0-9-]{0,63}")
                || mode != null && !List.of("DEMO", "MODEL").contains(mode) || from != null && until != null && !from.isBefore(until))
            throw new IllegalArgumentException("Invalid history filters");
    }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    Sql sql() {
        var conditions = new ArrayList<String>(); var arguments = new ArrayList<Object>();
        if (query != null) {
            String term = "%" + query.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            conditions.add("(LOWER(question_text) LIKE ? ESCAPE '!' OR LOWER(service_name) LIKE ? ESCAPE '!' OR LOWER(service_id) LIKE ? ESCAPE '!')");
            arguments.addAll(List.of(term, term, term));
        }
        if (service != null) { conditions.add("service_id = ?"); arguments.add(service); }
        if (status != null) { conditions.add("status = ?"); arguments.add(status.name()); }
        if (mode != null) { conditions.add("execution_mode = ?"); arguments.add(mode); }
        if (from != null) { conditions.add("created_at >= ?"); arguments.add(java.time.OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC)); }
        if (until != null) { conditions.add("created_at < ?"); arguments.add(java.time.OffsetDateTime.ofInstant(until, java.time.ZoneOffset.UTC)); }
        return new Sql(conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions), arguments);
    }
    record Sql(String clause, List<Object> arguments) {}
}
