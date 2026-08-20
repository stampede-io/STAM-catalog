package com.stampedeio.catalog.seed;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stampedeio.catalog.domain.Event;
import com.stampedeio.catalog.domain.EventRepository;
import com.stampedeio.catalog.domain.Seat;
import com.stampedeio.catalog.domain.SeatRepository;
import com.stampedeio.catalog.domain.Show;
import com.stampedeio.catalog.domain.ShowRepository;
import com.stampedeio.catalog.domain.Venue;
import com.stampedeio.catalog.domain.VenueRepository;

@RestController
@RequestMapping("/api/test-seed")
@Profile("dev")
public class TestSeedController {

    private static final Logger log = LoggerFactory.getLogger(TestSeedController.class);
    private static final String SEED_VENUE = "E2E Test Venue";

    private final VenueRepository venues;
    private final EventRepository events;
    private final ShowRepository shows;
    private final SeatRepository seats;

    public TestSeedController(VenueRepository venues, EventRepository events,
            ShowRepository shows, SeatRepository seats) {
        this.venues = venues;
        this.events = events;
        this.shows = shows;
        this.seats = seats;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> seed() {
        if (venues.existsByName(SEED_VENUE)) {
            log.info("E2E seed data already present; returning existing IDs.");
            return ResponseEntity.ok(buildResponse());
        }

        Venue venue = venues.save(new Venue(SEED_VENUE, "42 Test Lane", 100));

        Event event = new Event(venue.getId(), "E2E Flash Sale", "End-to-end test event");
        event.setStatus(Event.Status.PUBLISHED);
        events.save(event);

        Instant tomorrow = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Show show = shows.save(new Show(event.getId(), tomorrow, tomorrow.plus(2, ChronoUnit.HOURS)));

        List<Seat> batch = new ArrayList<>(20);
        for (int row = 0; row < 2; row++) {
            String rowLabel = String.valueOf((char) ('A' + row));
            for (int num = 1; num <= 10; num++) {
                batch.add(new Seat(show.getId(), "GA", rowLabel, num, 5000L));
            }
        }
        seats.saveAll(batch);

        log.info("E2E seed loaded: venue={}, event={}, show={}, seats={}",
                venue.getId(), event.getId(), show.getId(), batch.size());

        return ResponseEntity.ok(buildResponse());
    }

    @DeleteMapping
    @Transactional
    public ResponseEntity<Void> cleanup() {
        venues.findAll().stream()
                .filter(v -> SEED_VENUE.equals(v.getName()))
                .findFirst()
                .ifPresent(venue -> {
                    events.findAll().stream()
                            .filter(e -> venue.getId().equals(e.getVenueId()))
                            .forEach(event -> {
                                shows.findAll().stream()
                                        .filter(s -> event.getId().equals(s.getEventId()))
                                        .forEach(show -> {
                                            seats.findByShowIdOrderBySectionAscRowLabelAscSeatNumberAsc(show.getId())
                                                    .forEach(seats::delete);
                                            shows.delete(show);
                                        });
                                events.delete(event);
                            });
                    venues.delete(venue);
                });
        log.info("E2E seed data cleaned up.");
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> buildResponse() {
        Map<String, Object> result = new LinkedHashMap<>();
        venues.findAll().stream()
                .filter(v -> SEED_VENUE.equals(v.getName()))
                .findFirst()
                .ifPresent(venue -> {
                    result.put("venueId", venue.getId());
                    events.findAll().stream()
                            .filter(e -> venue.getId().equals(e.getVenueId()))
                            .findFirst()
                            .ifPresent(event -> {
                                result.put("eventId", event.getId());
                                shows.findAll().stream()
                                        .filter(s -> event.getId().equals(s.getEventId()))
                                        .findFirst()
                                        .ifPresent(show -> {
                                            result.put("showId", show.getId());
                                            result.put("seatCount",
                                                    seats.countByShowId(show.getId()));
                                        });
                            });
                });
        return result;
    }
}
