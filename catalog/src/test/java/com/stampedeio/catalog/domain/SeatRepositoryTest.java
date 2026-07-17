package com.stampedeio.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("slice")
class SeatRepositoryTest {

    @Autowired
    SeatRepository seats;

    @Autowired
    ShowRepository shows;

    @Autowired
    EventRepository events;

    @Autowired
    VenueRepository venues;

    @Test
    void seatsAreOrderedAndCountable() {
        Venue venue = venues.save(new Venue("Seat Arena", "1 St", 100));
        Event event = events.save(new Event(venue.getId(), "Seat Event", null));
        Instant t0 = Instant.now().plus(1, ChronoUnit.DAYS);
        Show show = shows.save(new Show(event.getId(), t0, t0.plus(1, ChronoUnit.HOURS)));

        List<Seat> batch = new ArrayList<>();
        for (int i = 5; i >= 1; i--) {
            batch.add(new Seat(show.getId(), "GA", "A", i, 1000L));
        }
        seats.saveAll(batch);

        List<Seat> ordered = seats.findByShowIdOrderBySectionAscRowLabelAscSeatNumberAsc(show.getId());
        assertThat(ordered).extracting(Seat::getSeatNumber).containsExactly(1, 2, 3, 4, 5);
        assertThat(seats.countByShowId(show.getId())).isEqualTo(5);
    }
}
