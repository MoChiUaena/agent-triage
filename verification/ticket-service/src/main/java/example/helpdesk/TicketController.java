package example.helpdesk;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tickets")
class TicketController {
    private final AssignmentGateway gateway;
    TicketController(AssignmentGateway gateway) { this.gateway = gateway; }

    @GetMapping("/{id}")
    Map<String, Object> ticket(@PathVariable String id) {
        if (!id.matches("[a-zA-Z0-9-]{1,40}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid ticket id");
        try {
            Map<?, ?> assignment = gateway.lookup(id);
            return Map.of("id", id, "assigned", assignment != null && Boolean.TRUE.equals(assignment.get("assigned")));
        } catch (ResourceAccessException e) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Assignment request did not complete");
        }
    }
}
