package com.stampedeio.catalog.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShowRepository extends JpaRepository<Show, UUID> {

    List<Show> findByEventIdOrderByStartsAtAsc(UUID eventId);

    @Query("SELECT s FROM Show s WHERE (:cursor IS NULL OR s.id > :cursor) ORDER BY s.id ASC")
    List<Show> findPage(@Param("cursor") UUID cursor, Limit limit);
}
