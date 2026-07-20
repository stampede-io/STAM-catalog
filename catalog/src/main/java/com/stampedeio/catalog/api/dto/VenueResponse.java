package com.stampedeio.catalog.api.dto;

import java.util.UUID;

import com.stampedeio.catalog.domain.Venue;

public record VenueResponse(UUID id, String name, String address, int capacity) {

    public static VenueResponse from(Venue v) {
        return new VenueResponse(v.getId(), v.getName(), v.getAddress(), v.getCapacity());
    }
}
