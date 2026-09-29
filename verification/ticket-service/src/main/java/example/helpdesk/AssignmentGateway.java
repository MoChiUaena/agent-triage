package example.helpdesk;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class AssignmentGateway {
    private final RestClient assignments;

    AssignmentGateway(RestClient.Builder builder, @Value("${triage.sdk.downstream-base-url}") String origin) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        factory.setReadTimeout(Duration.ofMillis(250));
        assignments = builder.baseUrl(origin).requestFactory(factory).build();
    }

    Map<?, ?> lookup(String ticketId) {
        return assignments.get().uri("/assignments/{id}", ticketId).retrieve().body(Map.class);
    }
}
