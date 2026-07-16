package com.stampedeio.catalog.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findByShowIdOrderBySectionAscRowLabelAscSeatNumberAsc(UUID showId);

    long countByShowId(UUID showId);
}
