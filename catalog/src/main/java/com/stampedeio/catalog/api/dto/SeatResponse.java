package com.stampedeio.catalog.api.dto;

import java.util.UUID;

import com.stampedeio.catalog.domain.Seat;

public record SeatResponse(
        UUID id,
        UUID showId,
        String section,
        String rowLabel,
        int seatNumber,
        long priceCents,
        String availability) {

    public static SeatResponse from(Seat s) {
        return new SeatResponse(
                s.getId(),
                s.getShowId(),
                s.getSection(),
                s.getRowLabel(),
                s.getSeatNumber(),
                s.getPriceCents(),
                s.getAvailability().name());
    }
}
