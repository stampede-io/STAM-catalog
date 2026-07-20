package com.stampedeio.catalog.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.stampedeio.catalog.cache.SeatAvailabilityCacheService;
import com.stampedeio.catalog.domain.Seat;
import com.stampedeio.catalog.domain.SeatRepository;
import com.stampedeio.catalog.domain.Show;
import com.stampedeio.catalog.domain.ShowRepository;
import com.stampedeio.catalog.domain.Event;
import com.stampedeio.catalog.domain.EventRepository;
import com.stampedeio.catalog.domain.Venue;
import com.stampedeio.catalog.domain.VenueRepository;

@SpringBootTest(properties = {
        "catalog.projection.enabled=true",
        "catalog.cache.availability-ttl-seconds=60"
})
@Testcontainers
class SeatAvailabilityProjectorTest {

    private static final String TOPIC = "reservations.events";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @MockitoSpyBean
    SeatRepository seatRepository;

    @Autowired
    SeatAvailabilityCacheService cacheService;

    @Autowired
    VenueRepository venueRepository;

    @Autowired
    EventRepository eventRepository;

    @Autowired
    ShowRepository showRepository;

    @Autowired
    ProcessedEventRepository processedEventRepository;

    @Autowired
    ProjectionOffsetRepository projectionOffsetRepository;

    private KafkaTemplate<String, Object> kafkaTemplate;

    private UUID showId;
    private UUID seatId1;
    private UUID seatId2;

    @BeforeEach
    void setUp() {
        Map<String, Object> producerProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        kafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));

        Venue venue = new Venue("Test Venue " + UUID.randomUUID(), "1 Test St", 100);
        venueRepository.save(venue);

        Event event = new Event(venue.getId(), "Test Event", "desc");
        eventRepository.save(event);

        Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS);
        Show show = new Show(event.getId(), startsAt, startsAt.plus(2, ChronoUnit.HOURS));
        showRepository.save(show);
        showId = show.getId();

        Seat seat1 = new Seat(showId, "GA", "A", 1, 5000L);
        Seat seat2 = new Seat(showId, "GA", "A", 2, 5000L);
        seatRepository.save(seat1);
        seatRepository.save(seat2);
        seatId1 = seat1.getId();
        seatId2 = seat2.getId();
    }

    @Test
    void seatsHeldEvent_updatesAvailabilityToHeld() {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        Map<String, Object> envelope = Map.of(
                "eventId", eventId.toString(),
                "eventType", "SeatsHeld",
                "version", 1,
                "occurredAt", Instant.now().toString(),
                "correlationId", UUID.randomUUID().toString(),
                "aggregateId", reservationId.toString(),
                "payload", Map.of(
                        "reservationId", reservationId.toString(),
                        "showId", showId.toString(),
                        "seatIds", List.of(seatId1.toString(), seatId2.toString()),
                        "status", "HELD",
                        "correlationId", UUID.randomUUID().toString()));

        kafkaTemplate.send(TOPIC, reservationId.toString(), envelope);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            Seat s1 = seatRepository.findById(seatId1).orElseThrow();
            Seat s2 = seatRepository.findById(seatId2).orElseThrow();
            assertThat(s1.getAvailability()).isEqualTo(Seat.Availability.HELD);
            assertThat(s2.getAvailability()).isEqualTo(Seat.Availability.HELD);
        });

        assertThat(processedEventRepository.existsById(eventId)).isTrue();
    }

    @Test
    void seatsReleasedEvent_updatesAvailabilityToAvailable() {
        seatRepository.findById(seatId1).ifPresent(s -> {
            s.setAvailability(Seat.Availability.HELD);
            seatRepository.save(s);
        });

        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        Map<String, Object> envelope = Map.of(
                "eventId", eventId.toString(),
                "eventType", "SeatsReleased",
                "version", 1,
                "occurredAt", Instant.now().toString(),
                "correlationId", UUID.randomUUID().toString(),
                "aggregateId", reservationId.toString(),
                "payload", Map.of(
                        "reservationId", reservationId.toString(),
                        "showId", showId.toString(),
                        "seatIds", List.of(seatId1.toString()),
                        "status", "RELEASED",
                        "correlationId", UUID.randomUUID().toString()));

        kafkaTemplate.send(TOPIC, reservationId.toString(), envelope);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            Seat s1 = seatRepository.findById(seatId1).orElseThrow();
            assertThat(s1.getAvailability()).isEqualTo(Seat.Availability.AVAILABLE);
        });
    }

    @Test
    void holdExpiredEvent_updatesAvailabilityToAvailable() {
        seatRepository.findById(seatId1).ifPresent(s -> {
            s.setAvailability(Seat.Availability.HELD);
            seatRepository.save(s);
        });

        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        Map<String, Object> envelope = Map.of(
                "eventId", eventId.toString(),
                "eventType", "HoldExpired",
                "version", 1,
                "occurredAt", Instant.now().toString(),
                "correlationId", UUID.randomUUID().toString(),
                "aggregateId", reservationId.toString(),
                "payload", Map.of(
                        "reservationId", reservationId.toString(),
                        "showId", showId.toString(),
                        "seatIds", List.of(seatId1.toString()),
                        "status", "EXPIRED",
                        "correlationId", UUID.randomUUID().toString()));

        kafkaTemplate.send(TOPIC, reservationId.toString(), envelope);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            Seat s1 = seatRepository.findById(seatId1).orElseThrow();
            assertThat(s1.getAvailability()).isEqualTo(Seat.Availability.AVAILABLE);
        });
    }

    @Test
    void duplicateEvent_isIdempotent() {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        Map<String, Object> envelope = Map.of(
                "eventId", eventId.toString(),
                "eventType", "SeatsHeld",
                "version", 1,
                "occurredAt", Instant.now().toString(),
                "correlationId", UUID.randomUUID().toString(),
                "aggregateId", reservationId.toString(),
                "payload", Map.of(
                        "reservationId", reservationId.toString(),
                        "showId", showId.toString(),
                        "seatIds", List.of(seatId1.toString()),
                        "status", "HELD",
                        "correlationId", UUID.randomUUID().toString()));

        kafkaTemplate.send(TOPIC, reservationId.toString(), envelope);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(processedEventRepository.existsById(eventId)).isTrue());

        // Send same event again
        kafkaTemplate.send(TOPIC, reservationId.toString(), envelope);

        // Brief wait to make sure second message is processed
        await().pollDelay(Duration.ofSeconds(2)).atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            // Should still be only one processed_event entry
            assertThat(processedEventRepository.count()).isGreaterThanOrEqualTo(1);
            Seat s1 = seatRepository.findById(seatId1).orElseThrow();
            assertThat(s1.getAvailability()).isEqualTo(Seat.Availability.HELD);
        });
    }

    @Test
    void projectionOffset_isTracked() {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        Map<String, Object> envelope = Map.of(
                "eventId", eventId.toString(),
                "eventType", "SeatsHeld",
                "version", 1,
                "occurredAt", Instant.now().toString(),
                "correlationId", UUID.randomUUID().toString(),
                "aggregateId", reservationId.toString(),
                "payload", Map.of(
                        "reservationId", reservationId.toString(),
                        "showId", showId.toString(),
                        "seatIds", List.of(seatId1.toString()),
                        "status", "HELD",
                        "correlationId", UUID.randomUUID().toString()));

        kafkaTemplate.send(TOPIC, reservationId.toString(), envelope);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            List<ProjectionOffset> offsets = projectionOffsetRepository.findAll();
            assertThat(offsets).isNotEmpty();
            assertThat(offsets.get(0).getCommittedOffset()).isGreaterThanOrEqualTo(0);
        });
    }

    // STAM-29 AC2: SeatsHeld/SeatsReleased invalidate the Redis cache for that
    // show as soon as the projector's DB transaction commits.
    @Test
    void seatsHeldEvent_evictsAvailabilityCache() {
        // Prime the cache: this is the one-and-only expected DB read.
        cacheService.getSeats(showId);
        verify(seatRepository, times(1)).findByShowIdOrderBySectionAscRowLabelAscSeatNumberAsc(showId);

        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        Map<String, Object> envelope = Map.of(
                "eventId", eventId.toString(),
                "eventType", "SeatsHeld",
                "version", 1,
                "occurredAt", Instant.now().toString(),
                "correlationId", UUID.randomUUID().toString(),
                "aggregateId", reservationId.toString(),
                "payload", Map.of(
                        "reservationId", reservationId.toString(),
                        "showId", showId.toString(),
                        "seatIds", List.of(seatId1.toString(), seatId2.toString()),
                        "status", "HELD",
                        "correlationId", UUID.randomUUID().toString()));

        kafkaTemplate.send(TOPIC, reservationId.toString(), envelope);

        // Once the projector's transaction commits, the next read must miss
        // the cache and hit Postgres again, observing the updated state.
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            cacheService.getSeats(showId);
            verify(seatRepository, times(2)).findByShowIdOrderBySectionAscRowLabelAscSeatNumberAsc(showId);
        });
    }
}
