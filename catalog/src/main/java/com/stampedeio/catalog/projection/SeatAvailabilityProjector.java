package com.stampedeio.catalog.projection;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.stampedeio.catalog.domain.Seat;
import com.stampedeio.catalog.domain.SeatRepository;

@Component
public class SeatAvailabilityProjector {

    private static final Logger log = LoggerFactory.getLogger(SeatAvailabilityProjector.class);
    private static final String GROUP_ID = "catalog-availability-projector";

    private final SeatRepository seatRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ProjectionOffsetRepository projectionOffsetRepository;

    public SeatAvailabilityProjector(SeatRepository seatRepository,
                                     ProcessedEventRepository processedEventRepository,
                                     ProjectionOffsetRepository projectionOffsetRepository) {
        this.seatRepository = seatRepository;
        this.processedEventRepository = processedEventRepository;
        this.projectionOffsetRepository = projectionOffsetRepository;
    }

    @KafkaListener(
            topics = "reservations.events",
            groupId = GROUP_ID,
            containerFactory = "kafkaListenerContainerFactory",
            autoStartup = "${catalog.projection.enabled:true}"
    )
    @Transactional
    public void onReservationEvent(ConsumerRecord<String, Map<String, Object>> record,
                                   Acknowledgment ack) {
        Map<String, Object> envelope = record.value();
        if (envelope == null) {
            ack.acknowledge();
            return;
        }

        String eventType = (String) envelope.get("eventType");
        String eventIdStr = (String) envelope.get("eventId");
        if (eventIdStr == null || eventType == null) {
            ack.acknowledge();
            return;
        }

        UUID eventId = UUID.fromString(eventIdStr);

        if (!tryMarkProcessed(eventId)) {
            log.debug("Duplicate event {}, skipping", eventId);
            ack.acknowledge();
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = envelope.get("payload") instanceof String payloadStr
                ? parsePayload(payloadStr)
                : (Map<String, Object>) envelope.get("payload");

        if (payload == null) {
            ack.acknowledge();
            return;
        }

        switch (eventType) {
            case "SeatsHeld" -> updateSeats(payload, Seat.Availability.HELD);
            case "SeatsReleased", "HoldExpired" -> updateSeats(payload, Seat.Availability.AVAILABLE);
            default -> log.debug("Ignoring event type: {}", eventType);
        }

        saveOffset(record);
        ack.acknowledge();
        log.debug("Processed {} event {}", eventType, eventId);
    }

    private boolean tryMarkProcessed(UUID eventId) {
        try {
            processedEventRepository.saveAndFlush(new ProcessedEvent(eventId));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    private void updateSeats(Map<String, Object> payload, Seat.Availability targetState) {
        String showIdStr = (String) payload.get("showId");
        if (showIdStr == null) return;

        UUID showId = UUID.fromString(showIdStr);

        @SuppressWarnings("unchecked")
        List<String> seatIdStrs = (List<String>) payload.get("seatIds");
        if (seatIdStrs == null || seatIdStrs.isEmpty()) return;

        List<UUID> seatIds = seatIdStrs.stream().map(UUID::fromString).toList();

        List<Seat> seats = seatRepository.findAllById(seatIds);
        for (Seat seat : seats) {
            if (seat.getShowId().equals(showId)) {
                seat.setAvailability(targetState);
            }
        }
        seatRepository.saveAll(seats);
    }

    private void saveOffset(ConsumerRecord<String, ?> record) {
        ProjectionOffset.Key key = new ProjectionOffset.Key(GROUP_ID, record.topic(), record.partition());
        ProjectionOffset offset = projectionOffsetRepository.findById(key)
                .orElse(new ProjectionOffset(GROUP_ID, record.topic(), record.partition(), record.offset()));
        offset.setCommittedOffset(record.offset());
        projectionOffsetRepository.save(offset);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String json) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse payload JSON: {}", e.getMessage());
            return null;
        }
    }
}
