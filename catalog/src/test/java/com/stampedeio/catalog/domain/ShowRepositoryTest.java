package com.stampedeio.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class ShowRepositoryTest {

    @Autowired
    ShowRepository shows;

    @Autowired
    EventRepository events;

    @Autowired
    VenueRepository venues;

    @Test
    void findsShowsByEventOrderedByStart() {
        Venue venue = venues.save(new Venue("Show Arena", "1 St", 100));
        Event event = events.save(new Event(venue.getId(), "Show Event", null));
        Instant t0 = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        shows.save(new Show(event.getId(), t0.plus(2, ChronoUnit.HOURS), t0.plus(3, ChronoUnit.HOURS)));
        shows.save(new Show(event.getId(), t0, t0.plus(1, ChronoUnit.HOURS)));

        List<Show> found = shows.findByEventIdOrderByStartsAtAsc(event.getId());
        assertThat(found).hasSize(2);
        assertThat(found.get(0).getStartsAt()).isEqualTo(t0);
    }
}
