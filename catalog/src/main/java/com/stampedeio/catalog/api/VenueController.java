package com.stampedeio.catalog.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.stampedeio.catalog.api.dto.PageResponse;
import com.stampedeio.catalog.api.dto.VenueRequest;
import com.stampedeio.catalog.api.dto.VenueResponse;
import com.stampedeio.catalog.domain.Venue;
import com.stampedeio.catalog.domain.VenueRepository;
import com.stampedeio.catalog.exception.DuplicateVenueNameException;
import com.stampedeio.catalog.exception.ResourceNotFoundException;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/venues")
@Tag(name = "Venues")
public class VenueController {

    private final VenueRepository venues;

    public VenueController(VenueRepository venues) {
        this.venues = venues;
    }

    @PostMapping
    public ResponseEntity<VenueResponse> create(@Valid @RequestBody VenueRequest req) {
        if (venues.existsByName(req.name())) {
            throw new DuplicateVenueNameException(req.name());
        }
        Venue saved = venues.save(new Venue(req.name(), req.address(), req.capacity()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(saved.getId()).toUri();
        return ResponseEntity.created(location).body(VenueResponse.from(saved));
    }

    @GetMapping("/{id}")
    public VenueResponse get(@PathVariable UUID id) {
        return venues.findById(id)
                .map(VenueResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Venue", id));
    }

    @GetMapping
    public PageResponse<VenueResponse> list(
            @RequestParam(required = false) UUID cursor,
            @RequestParam(defaultValue = "20") int limit) {
        List<Venue> page = venues.findPage(cursor, Limit.of(limit));
        String nextCursor = page.size() < limit ? null : page.get(page.size() - 1).getId().toString();
        return new PageResponse<>(page.stream().map(VenueResponse::from).toList(), nextCursor);
    }
}
