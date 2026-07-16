package com.stampedeio.catalog.seed;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.stampedeio.catalog.domain.Event;
import com.stampedeio.catalog.domain.EventRepository;
import com.stampedeio.catalog.domain.Seat;
import com.stampedeio.catalog.domain.SeatRepository;
import com.stampedeio.catalog.domain.Show;
import com.stampedeio.catalog.domain.ShowRepository;
import com.stampedeio.catalog.domain.Venue;
import com.stampedeio.catalog.domain.VenueRepository;

@Component
@Profile("dev")
public class DataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataLoader.class);
    private static final String SEED_VENUE_NAME = "Stampede Arena";

    private final VenueRepository venues;
    private final EventRepository events;
    private final ShowRepository shows;
    private final SeatRepository seats;

    public DataLoader(VenueRepository venues, EventRepository events, ShowRepository shows, SeatRepository seats) {
        this.venues = venues;
        this.events = events;
        this.shows = shows;
        this.seats = seats;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (venues.existsByName(SEED_VENUE_NAME)) {
            log.info("Dev seed data already present; skipping.");
            return;
        }

        Venue arena = venues.save(new Venue(SEED_VENUE_NAME, "1 Overflow Street", 200));
        Event festival = events.save(new Event(arena.getId(), "Opening Night Festival",
                "The inaugural STAMPEDE festival, celebrating the first show of the platform."));
        festival.setStatus(Event.Status.PUBLISHED);
        events.save(festival);

        Instant tomorrow = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Show showOne = shows.save(new Show(festival.getId(), tomorrow, tomorrow.plus(2, ChronoUnit.HOURS)));
        Show showTwo = shows.save(new Show(festival.getId(), tomorrow.plus(1, ChronoUnit.DAYS),
                tomorrow.plus(1, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS)));

        List<Seat> batch = new ArrayList<>(200);
        for (Show show : List.of(showOne, showTwo)) {
            for (int row = 0; row < 10; row++) {
                String rowLabel = String.valueOf((char) ('A' + row));
                for (int number = 1; number <= 10; number++) {
                    batch.add(new Seat(show.getId(), "GA", rowLabel, number, 5000L));
                }
            }
        }
        seats.saveAll(batch);

        log.info("Dev seed loaded: 1 venue, 1 event, 2 shows, {} seats.", batch.size());
    }
}
