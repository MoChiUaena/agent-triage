package example.checkout;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpServerErrorException;
import java.time.Duration;

@RestController
public class CheckoutController {
    private final RestTemplate inventory;
    private final RestTemplate payment;

    public CheckoutController(RestTemplateBuilder builder, @Value("${checkout.inventory-url}") String inventoryUrl,
                              @Value("${checkout.payment-url}") String paymentUrl) {
        inventory = builder.rootUri(inventoryUrl).requestFactory(SimpleClientHttpRequestFactory::new)
            .connectTimeout(Duration.ofSeconds(1)).readTimeout(Duration.ofMillis(100)).build();
        payment = builder.rootUri(paymentUrl).requestFactory(SimpleClientHttpRequestFactory::new)
            .connectTimeout(Duration.ofSeconds(1)).readTimeout(Duration.ofSeconds(1)).build();
    }

    @GetMapping("/api/checkout")
    public String checkout() {
        String stock;
        try { stock = inventory.getForObject("/stock", String.class); }
        catch (RestClientException timeout) { stock = "unavailable"; }
        String paid;
        try { paid = payment.getForObject("/payment", String.class); }
        catch (HttpServerErrorException failed) { paid = "failed"; }
        return "stock=" + stock + ";payment=" + paid;
    }
}
