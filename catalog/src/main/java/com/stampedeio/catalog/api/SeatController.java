package com.stampedeio.catalog.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stampedeio.catalog.api.dto.SeatResponse;
import com.stampedeio.catalog.cache.SeatAvailabilityCacheService;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/shows/{showId}/seats")
@Tag(name = "Seats")
public class SeatController {

    private final SeatAvailabilityCacheService availability;

    public SeatController(SeatAvailabilityCacheService availability) {
        this.availability = availability;
    }

    @GetMapping
    public List<SeatResponse> listSeats(@PathVariable UUID showId) {
        return availability.getSeats(showId);
    }
}
