package com.omkar.ledger.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateWebhookEndpointRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$", message = "must be 1-64 chars of letters, digits, _ -")
        String merchantId,
        @NotBlank @Size(max = 2048) String url,
        @NotEmpty List<@NotBlank String> enabledEvents) {
}
