package com.stampedeio.catalog.projection;

public class NonRetryableProjectionException extends RuntimeException {

    public NonRetryableProjectionException(String message) {
        super(message);
    }

    public NonRetryableProjectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
