package com.stampedeio.catalog.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.stampedeio.catalog.domain.Show;

public record ShowResponse(
        UUID id,
        UUID eventId,
        Instant startsAt,
        Instant endsAt,
        String status) {

    public static ShowResponse from(Show s) {
        return new ShowResponse(s.getId(), s.getEventId(), s.getStartsAt(), s.getEndsAt(), s.getStatus().name());
    }
}
