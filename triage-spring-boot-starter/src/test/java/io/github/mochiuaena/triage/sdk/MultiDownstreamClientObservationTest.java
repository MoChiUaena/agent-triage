package io.github.mochiuaena.triage.sdk;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

class MultiDownstreamClientObservationTest {
    @Test void webClientKeepsTimeoutWithInventoryWhenPaymentSucceeds() {
        var properties = new TriageObservationProperties();
        properties.setServiceId("catalog-service");
        properties.setDownstreams(List.of(
            new TriageObservationProperties.Downstream("inventory-service", URI.create("http://127.0.0.1:18084")),
            new TriageObservationProperties.Downstream("payment-service", URI.create("http://127.0.0.1:18085"))));
        properties.validate();
        var client = WebClient.builder().filter(new TriageWebClientFilter(properties))
            .exchangeFunction(request -> request.url().getPort() == 18084
                ? Mono.error(new SocketTimeoutException("private-inventory-timeout"))
                : Mono.just(ClientResponse.create(HttpStatus.OK).body("ok").build())).build();
        var context = new TriageRequestFilter.Context();
        TriageRequestFilter.CURRENT.set(context);
        try {
            assertThatThrownBy(() -> client.get().uri("http://127.0.0.1:18084/items").retrieve().bodyToMono(String.class).block())
                .isNotNull();
            assertThat(client.get().uri("http://127.0.0.1:18085/payments").retrieve().bodyToMono(String.class).block())
                .isEqualTo("ok");
        } finally { TriageRequestFilter.CURRENT.remove(); }
        var completed = context.finish();
        assertThat(completed.downstreams()).hasSize(2);
        assertThat(completed.downstreams().get("inventory-service").timeout()).isTrue();
        assertThat(completed.downstreams().get("payment-service").timeout()).isFalse();
    }

    @Test void inventoryTimeoutDoesNotTurnTheSuccessfulPaymentCallIntoATimeout() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(RestTemplateAutoConfiguration.class,
                TriageObservationAutoConfiguration.class))
            .withPropertyValues("triage.sdk.enabled=true", "triage.sdk.service-id=catalog-service",
                "triage.sdk.downstreams[0].id=inventory-service", "triage.sdk.downstreams[0].base-url=http://127.0.0.1:18084",
                "triage.sdk.downstreams[1].id=payment-service", "triage.sdk.downstreams[1].base-url=http://127.0.0.1:18085")
            .run(application -> {
                assertThat(application).hasNotFailed();
                var client = application.getBean(RestTemplateBuilder.class).build();
                var server = MockRestServiceServer.bindTo(client).build();
                server.expect(requestTo("http://127.0.0.1:18084/items"))
                    .andRespond(request -> { throw new SocketTimeoutException("private-inventory-timeout"); });
                server.expect(requestTo("http://127.0.0.1:18085/payments"))
                    .andRespond(request -> new MockClientHttpResponse("ok".getBytes(StandardCharsets.UTF_8), HttpStatus.OK));
                var context = new TriageRequestFilter.Context();
                TriageRequestFilter.CURRENT.set(context);
                try {
                    assertThatThrownBy(() -> client.getForObject("http://127.0.0.1:18084/items", String.class))
                        .isInstanceOf(ResourceAccessException.class);
                    assertThat(client.getForObject("http://127.0.0.1:18085/payments", String.class)).isEqualTo("ok");
                } finally { TriageRequestFilter.CURRENT.remove(); }
                var completed = context.finish();
                assertThat(completed.downstreams()).hasSize(2);
                assertThat(completed.downstreams().get("inventory-service").timeout()).isTrue();
                assertThat(completed.downstreams().get("payment-service").timeout()).isFalse();
                assertThat(completed.downstreams().get("payment-service").millis()).isGreaterThan(0);
                server.verify();
            });
    }
}
