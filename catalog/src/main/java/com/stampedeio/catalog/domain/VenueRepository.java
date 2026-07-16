package com.stampedeio.catalog.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VenueRepository extends JpaRepository<Venue, UUID> {

    boolean existsByName(String name);

    @Query("SELECT v FROM Venue v WHERE (:cursor IS NULL OR v.id > :cursor) ORDER BY v.id ASC")
    List<Venue> findPage(@Param("cursor") UUID cursor, Limit limit);
}
