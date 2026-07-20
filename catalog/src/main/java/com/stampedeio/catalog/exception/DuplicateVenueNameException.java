package com.stampedeio.catalog.exception;

public class DuplicateVenueNameException extends ConflictException {

    public DuplicateVenueNameException(String name) {
        super("Venue '" + name + "' already exists");
    }
}
