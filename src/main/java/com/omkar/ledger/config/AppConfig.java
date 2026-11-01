package com.omkar.ledger.config;

import com.omkar.ledger.payment.FeeCalculator;
import com.omkar.ledger.webhook.BackoffSchedule;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Plain object factories. A {@code @Bean} method is how you register an object you construct
 * yourself in the Spring container (dependency injection) - roughly "a module-level singleton,
 * but injectable and replaceable in tests".
 */
@Configuration
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public FeeCalculator feeCalculator(LedgerProperties properties) {
        return new FeeCalculator(properties.fees().percentBps(), properties.fees().fixedMinor());
    }

    @Bean
    public BackoffSchedule webhookBackoffSchedule(LedgerProperties properties) {
        var webhooks = properties.webhooks();
        return new BackoffSchedule(webhooks.retrySchedule(), webhooks.jitterRatio(),
                () -> ThreadLocalRandom.current().nextDouble());
    }

    @Bean
    public HttpClient webhookHttpClient(LedgerProperties properties) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.webhooks().requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    public OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Payments Ledger API")
                        .version("v1")
                        .description("Double-entry ledger with idempotent payments, refunds, transfers, "
                                + "a transactional outbox and signed webhooks. Every POST requires an "
                                + "Idempotency-Key header."))
                .components(new Components().addSecuritySchemes("apiKey", new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(ApiKeyFilter.HEADER)))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"));
    }
}
