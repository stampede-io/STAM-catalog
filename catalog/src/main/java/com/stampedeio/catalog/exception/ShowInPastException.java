package com.stampedeio.catalog.exception;

import java.time.Instant;

public class ShowInPastException extends RuntimeException {

    public ShowInPastException(Instant startsAt) {
        super("Show start time " + startsAt + " is in the past");
    }
}
