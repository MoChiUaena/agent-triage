package example.helpdesk;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
class DefaultTicketService implements TicketService {
    private final AssignmentGateway gateway;
    DefaultTicketService(AssignmentGateway gateway) { this.gateway = gateway; }

    @Override public Map<String, Object> find(String id) {
        Map<?, ?> assignment = gateway.lookup(id);
        return TicketFormatter.view(id, assignment);
    }
}
