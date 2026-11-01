package com.omkar.ledger.webhook;

/** Outcome of one HTTP attempt. {@code statusCode} is null when no response was received. */
public record DeliveryResult(boolean successful, Integer statusCode, String error) {

    public static DeliveryResult success(int statusCode) {
        return new DeliveryResult(true, statusCode, null);
    }

    public static DeliveryResult failure(Integer statusCode, String error) {
        return new DeliveryResult(false, statusCode, error);
    }
}
