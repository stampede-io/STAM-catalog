package com.stampedeio.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class EventRepositoryTest {

    @Autowired
    EventRepository events;

    @Autowired
    VenueRepository venues;

    @Test
    void existsByVenueAndNameDetectsDuplicates() {
        Venue venue = venues.save(new Venue("Repo Arena", "1 Repo St", 100));
        events.save(new Event(venue.getId(), "Show A", null));

        assertThat(events.existsByVenueIdAndName(venue.getId(), "Show A")).isTrue();
        assertThat(events.existsByVenueIdAndName(venue.getId(), "Show B")).isFalse();
    }
}
