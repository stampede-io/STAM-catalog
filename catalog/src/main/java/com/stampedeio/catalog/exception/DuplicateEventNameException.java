package com.stampedeio.catalog.exception;

import java.util.UUID;

public class DuplicateEventNameException extends ConflictException {

    public DuplicateEventNameException(UUID venueId, String name) {
        super("Event '" + name + "' already exists for venue " + venueId);
    }
}
