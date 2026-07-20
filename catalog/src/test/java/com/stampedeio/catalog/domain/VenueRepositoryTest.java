package com.stampedeio.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Limit;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("slice")
class VenueRepositoryTest {

    @Autowired
    VenueRepository repo;

    @Test
    void savesAndFindsByName() {
        Venue saved = repo.save(new Venue("Test Arena", "1 Test St", 500));
        assertThat(repo.existsByName("Test Arena")).isTrue();
        assertThat(repo.findById(saved.getId())).get().extracting(Venue::getName).isEqualTo("Test Arena");
    }

    @Test
    void cursorPaginationReturnsRequestedSize() {
        for (int i = 0; i < 5; i++) {
            repo.save(new Venue("Venue " + i, "addr " + i, 100));
        }
        List<Venue> firstPage = repo.findPage(null, Limit.of(3));
        assertThat(firstPage).hasSize(3);
        List<Venue> secondPage = repo.findPage(firstPage.get(2).getId(), Limit.of(3));
        assertThat(secondPage).hasSize(2);
    }
}
