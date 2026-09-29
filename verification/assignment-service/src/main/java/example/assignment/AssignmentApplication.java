package example.assignment;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Local demo dependency. Delays are configured at launch; no remote fault-control endpoint. */
@SpringBootApplication
@RestController
public class AssignmentApplication {
    private final int delayMillis;
    public AssignmentApplication(@Value("${assignment.delay-millis:700}") int delayMillis) {
        if (delayMillis < 0 || delayMillis > 2000) throw new IllegalArgumentException("Assignment demo delay must be 0..2000 ms");
        this.delayMillis = delayMillis;
    }
    public static void main(String[] args) { SpringApplication.run(AssignmentApplication.class, args); }
    @GetMapping("/health") public Map<String,String> health() { return Map.of("status", "UP"); }
    @GetMapping("/assignments/{id}") public Map<String,Object> assignment(@PathVariable String id) throws InterruptedException {
        if (!id.matches("[a-zA-Z0-9-]{1,40}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid assignment identifier");
        Thread.sleep(delayMillis);
        return Map.of("assigned", true);
    }
}
