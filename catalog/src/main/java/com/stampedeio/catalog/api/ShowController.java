package com.stampedeio.catalog.api;

import java.net.URI;
import java.time.Instant;
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
import com.stampedeio.catalog.api.dto.ShowRequest;
import com.stampedeio.catalog.api.dto.ShowResponse;
import com.stampedeio.catalog.domain.EventRepository;
import com.stampedeio.catalog.domain.Show;
import com.stampedeio.catalog.domain.ShowRepository;
import com.stampedeio.catalog.exception.ResourceNotFoundException;
import com.stampedeio.catalog.exception.ShowInPastException;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/shows")
@Tag(name = "Shows")
public class ShowController {

    private final ShowRepository shows;
    private final EventRepository events;

    public ShowController(ShowRepository shows, EventRepository events) {
        this.shows = shows;
        this.events = events;
    }

    @PostMapping
    public ResponseEntity<ShowResponse> create(@Valid @RequestBody ShowRequest req) {
        if (!events.existsById(req.eventId())) {
            throw new ResourceNotFoundException("Event", req.eventId());
        }
        if (req.startsAt().isBefore(Instant.now())) {
            throw new ShowInPastException(req.startsAt());
        }
        if (!req.endsAt().isAfter(req.startsAt())) {
            throw new IllegalArgumentException("endsAt must be after startsAt");
        }
        Show saved = shows.save(new Show(req.eventId(), req.startsAt(), req.endsAt()));
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(saved.getId()).toUri();
        return ResponseEntity.created(location).body(ShowResponse.from(saved));
    }

    @GetMapping("/{id}")
    public ShowResponse get(@PathVariable UUID id) {
        return shows.findById(id)
                .map(ShowResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Show", id));
    }

    @GetMapping
    public PageResponse<ShowResponse> list(
            @RequestParam(required = false) UUID cursor,
            @RequestParam(defaultValue = "20") int limit) {
        List<Show> page = shows.findPage(cursor, Limit.of(limit));
        String nextCursor = page.size() < limit ? null : page.get(page.size() - 1).getId().toString();
        return new PageResponse<>(page.stream().map(ShowResponse::from).toList(), nextCursor);
    }
}
