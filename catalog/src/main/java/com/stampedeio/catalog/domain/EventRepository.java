package com.stampedeio.catalog.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, UUID> {

    boolean existsByVenueIdAndName(UUID venueId, String name);

    @Query("SELECT e FROM Event e WHERE (:cursor IS NULL OR e.id > :cursor) ORDER BY e.id ASC")
    List<Event> findPage(@Param("cursor") UUID cursor, Limit limit);
}
