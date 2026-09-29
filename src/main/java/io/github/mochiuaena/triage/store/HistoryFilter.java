package io.github.mochiuaena.triage.store;

import io.github.mochiuaena.triage.domain.TriageModel.Status;
import java.time.Instant;
import java.util.*;

public record HistoryFilter(String query, String service, Status status, String mode, Instant from, Instant until, String endpointId) {
    public static final String WHOLE_SERVICE = "SERVICE";
    public HistoryFilter(String query, String service, Status status, String mode, Instant from, Instant until) {
        this(query, service, status, mode, from, until, null);
    }
    public HistoryFilter {
        query = blank(query); service = blank(service); mode = blank(mode); endpointId = blank(endpointId);
        if (query != null && query.length() > 200 || service != null && !service.matches("[a-z][a-z0-9-]{0,63}")
                || mode != null && !List.of("DEMO", "MODEL").contains(mode) || from != null && until != null && !from.isBefore(until)
                || endpointId != null && !WHOLE_SERVICE.equals(endpointId) && (!endpointId.matches("EP-[a-f0-9]{32}") || service == null))
            throw new IllegalArgumentException("Invalid history filters");
    }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    Sql sql() {
        var conditions = new ArrayList<String>(); var arguments = new ArrayList<Object>();
        if (query != null) {
            String term = "%" + query.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            var columns = List.of("question_text", "service_name", "service_id", "endpoint_http_method", "endpoint_route", "endpoint_handler_class", "endpoint_handler_method");
            conditions.add("(" + String.join(" OR ", columns.stream().map(column -> "LOWER(" + column + ") LIKE ? ESCAPE '!'").toList()) + ")");
            arguments.addAll(Collections.nCopies(columns.size(), term));
        }
        if (service != null) { conditions.add("service_id = ?"); arguments.add(service); }
        if (WHOLE_SERVICE.equals(endpointId)) conditions.add("endpoint_id IS NULL");
        else if (endpointId != null) { conditions.add("endpoint_id = ?"); arguments.add(endpointId); }
        if (status != null) { conditions.add("status = ?"); arguments.add(status.name()); }
        if (mode != null) { conditions.add("execution_mode = ?"); arguments.add(mode); }
        if (from != null) { conditions.add("created_at >= ?"); arguments.add(java.time.OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC)); }
        if (until != null) { conditions.add("created_at < ?"); arguments.add(java.time.OffsetDateTime.ofInstant(until, java.time.ZoneOffset.UTC)); }
        return new Sql(conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions), arguments);
    }
    record Sql(String clause, List<Object> arguments) {}
}
