package com.stampedeio.catalog.api.dto;

import java.util.UUID;

import com.stampedeio.catalog.domain.Event;

public record EventResponse(UUID id, UUID venueId, UUID organizerId, String name, String description, String status) {

    public static EventResponse from(Event e) {
        return new EventResponse(e.getId(), e.getVenueId(), e.getOrganizerId(),
                e.getName(), e.getDescription(), e.getStatus().name());
    }
}
