package com.stampedeio.catalog.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stampedeio.catalog.api.dto.SeatResponse;
import com.stampedeio.catalog.domain.SeatRepository;
import com.stampedeio.catalog.domain.ShowRepository;
import com.stampedeio.catalog.exception.ResourceNotFoundException;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/shows/{showId}/seats")
@Tag(name = "Seats")
public class SeatController {

    private final SeatRepository seats;
    private final ShowRepository shows;

    public SeatController(SeatRepository seats, ShowRepository shows) {
        this.seats = seats;
        this.shows = shows;
    }

    @GetMapping
    public List<SeatResponse> listSeats(@PathVariable UUID showId) {
        if (!shows.existsById(showId)) {
            throw new ResourceNotFoundException("Show", showId);
        }
        return seats.findByShowIdOrderBySectionAscRowLabelAscSeatNumberAsc(showId).stream()
                .map(SeatResponse::from)
                .toList();
    }
}
