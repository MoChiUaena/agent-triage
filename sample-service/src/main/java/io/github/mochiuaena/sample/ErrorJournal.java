package io.github.mochiuaena.sample;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** JSON-lines error log written at the failure site and read for triage. */
@Component
public class ErrorJournal {
    private static final long MAX_BYTES = 2_000_000;
    private final Path file;
    private final ObjectMapper json;

    public ErrorJournal(@Value("${sample.error-log-file:./data/sample-errors.jsonl}") String path, ObjectMapper json) {
        this.file = Path.of(path).toAbsolutePath().normalize();
        this.json = json;
    }

    public synchronized void append(ObservationStore.ErrorEntry entry) {
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) >= MAX_BYTES) reset();
            Files.writeString(file, json.writeValueAsString(entry) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { throw new UncheckedIOException("Cannot append sample error log", e); }
    }

    public synchronized void reset() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) { throw new UncheckedIOException("Cannot reset sample error log", e); }
    }

    public synchronized List<ObservationStore.ErrorEntry> recent(Instant start, Instant end, int limit) {
        if (!Files.exists(file)) return List.of();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<ObservationStore.ErrorEntry> result = new ArrayList<>();
            for (int i = lines.size() - 1; i >= 0 && result.size() < limit; i--) {
                if (lines.get(i).isBlank()) continue;
                var entry = json.readValue(lines.get(i), ObservationStore.ErrorEntry.class);
                if (!entry.timestamp().isBefore(start) && !entry.timestamp().isAfter(end)) result.add(entry);
            }
            return List.copyOf(result);
        } catch (IOException e) { throw new UncheckedIOException("Cannot read sample error log", e); }
    }
}
