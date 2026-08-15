package com.stampedeio.catalog.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.stampedeio.catalog.api.dto.EventRequest;
import com.stampedeio.catalog.api.dto.EventResponse;
import com.stampedeio.catalog.api.dto.PageResponse;
import com.stampedeio.catalog.domain.Event;
import com.stampedeio.catalog.domain.EventRepository;
import com.stampedeio.catalog.domain.VenueRepository;
import com.stampedeio.catalog.exception.DuplicateEventNameException;
import com.stampedeio.catalog.exception.ResourceNotFoundException;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Events")
public class EventController {

    private final EventRepository events;
    private final VenueRepository venues;

    public EventController(EventRepository events, VenueRepository venues) {
        this.events = events;
        this.venues = venues;
    }

    @PostMapping
    @PreAuthorize("hasRole('ORGANIZER')")
    public ResponseEntity<EventResponse> create(@Valid @RequestBody EventRequest req,
                                                 @AuthenticationPrincipal Jwt jwt) {
        if (!venues.existsById(req.venueId())) {
            throw new ResourceNotFoundException("Venue", req.venueId());
        }
        if (events.existsByVenueIdAndName(req.venueId(), req.name())) {
            throw new DuplicateEventNameException(req.venueId(), req.name());
        }
        UUID organizerId = UUID.fromString(jwt.getClaimAsString("user_id"));
        Event saved = events.save(new Event(req.venueId(), req.name(), req.description(), organizerId));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(saved.getId()).toUri();
        return ResponseEntity.created(location).body(EventResponse.from(saved));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ORGANIZER')")
    public EventResponse update(@PathVariable UUID id,
                                @Valid @RequestBody EventRequest req,
                                @AuthenticationPrincipal Jwt jwt) {
        Event event = events.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Event", id));

        UUID callerId = UUID.fromString(jwt.getClaimAsString("user_id"));
        if (event.getOrganizerId() != null && !event.getOrganizerId().equals(callerId)) {
            throw new AccessDeniedException("You do not own this event");
        }

        if (!req.venueId().equals(event.getVenueId()) || !req.name().equals(event.getName())) {
            if (events.existsByVenueIdAndName(req.venueId(), req.name())) {
                throw new DuplicateEventNameException(req.venueId(), req.name());
            }
        }

        event.setName(req.name());
        event.setDescription(req.description());
        return EventResponse.from(events.save(event));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        Event event = events.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Event", id));
        events.delete(event);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public EventResponse get(@PathVariable UUID id) {
        return events.findById(id)
                .map(EventResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Event", id));
    }

    @GetMapping
    public PageResponse<EventResponse> list(
            @RequestParam(required = false) UUID cursor,
            @RequestParam(defaultValue = "20") int limit) {
        List<Event> page = events.findPage(cursor, Limit.of(limit));
        String nextCursor = page.size() < limit ? null : page.get(page.size() - 1).getId().toString();
        return new PageResponse<>(page.stream().map(EventResponse::from).toList(), nextCursor);
    }
}
