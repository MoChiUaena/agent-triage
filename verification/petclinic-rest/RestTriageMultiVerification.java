package org.springframework.samples.petclinic.triage;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** Disabled-by-default controller used only for the isolated public-project acceptance run. */
@RestController
@ConditionalOnProperty(name = "triage.verification.multi-enabled", havingValue = "true")
public class RestTriageMultiVerification {
    private final RestClient inventory;
    private final RestClient payment;

    public RestTriageMultiVerification(RestClient.Builder builder,
            @Value("${triage.verification.inventory-base-url}") URI inventoryUrl,
            @Value("${triage.verification.payment-base-url}") URI paymentUrl) {
        localOrigin(inventoryUrl); localOrigin(paymentUrl);
        var inventoryFactory = new SimpleClientHttpRequestFactory();
        inventoryFactory.setConnectTimeout(500); inventoryFactory.setReadTimeout(100);
        var paymentFactory = new SimpleClientHttpRequestFactory();
        paymentFactory.setConnectTimeout(500); paymentFactory.setReadTimeout(500);
        inventory = builder.requestFactory(inventoryFactory).baseUrl(inventoryUrl.toString()).build();
        payment = builder.requestFactory(paymentFactory).baseUrl(paymentUrl.toString()).build();
    }

    @GetMapping("/api/triage-multi/check")
    public Map<String, String> check(@RequestHeader(value = "X-Triage-Lab", required = false) String header,
                                     @RequestParam(defaultValue = "false") boolean failPayment, HttpServletRequest request) {
        if (!"1".equals(header) || !loopback(request.getRemoteAddr())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String stock;
        try { inventory.get().uri("/delay").retrieve().toBodilessEntity(); stock = "ok"; }
        catch (ResourceAccessException timeout) { stock = "timeout"; }
        String paid;
        try { payment.get().uri("/payment?fail=" + failPayment).retrieve().toBodilessEntity(); paid = "ok"; }
        catch (HttpServerErrorException error) { paid = "serverError"; }
        return Map.of("inventory", stock, "payment", paid);
    }

    private static void localOrigin(URI value) {
        if (value == null || !"http".equals(value.getScheme()) || !"127.0.0.1".equals(value.getHost())
            || value.getPort() < 1 || value.getPort() > 65535 || value.getUserInfo() != null
            || value.getQuery() != null || value.getFragment() != null
            || !(value.getPath().isEmpty() || "/".equals(value.getPath())))
            throw new IllegalArgumentException("Use two isolated loopback downstreams");
    }

    private static boolean loopback(String remote) {
        try { return InetAddress.getByName(remote).isLoopbackAddress(); }
        catch (Exception invalid) { return false; }
    }
}
