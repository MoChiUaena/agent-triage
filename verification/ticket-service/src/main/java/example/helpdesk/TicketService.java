package example.helpdesk;

import java.util.Map;

interface TicketService {
    Map<String, Object> find(String id);
}
