package com.stampedeio.catalog.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stampedeio.catalog.api.dto.SeatResponse;
import com.stampedeio.catalog.cache.SeatAvailabilityCacheService;
import com.stampedeio.catalog.domain.SeatRepository;
import com.stampedeio.catalog.exception.ResourceNotFoundException;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/shows/{showId}/seats")
@Tag(name = "Seats")
public class SeatController {

    private final SeatAvailabilityCacheService availability;
    private final SeatRepository seats;

    public SeatController(SeatAvailabilityCacheService availability, SeatRepository seats) {
        this.availability = availability;
        this.seats = seats;
    }

    @GetMapping
    public List<SeatResponse> listSeats(@PathVariable UUID showId) {
        return availability.getSeats(showId);
    }

    @GetMapping("/validate")
    public ResponseEntity<Void> validateSeats(@PathVariable UUID showId,
                                              @RequestParam("ids") List<UUID> ids) {
        long matched = seats.countByShowIdAndIdIn(showId, ids);
        if (matched != ids.size()) {
            throw new ResourceNotFoundException("Seat", ids);
        }
        return ResponseEntity.noContent().build();
    }
}
