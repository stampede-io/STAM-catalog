package com.stampedeio.catalog.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.stampedeio.catalog.domain.Event;
import com.stampedeio.catalog.domain.EventRepository;
import com.stampedeio.catalog.domain.Seat;
import com.stampedeio.catalog.domain.SeatRepository;
import com.stampedeio.catalog.domain.Show;
import com.stampedeio.catalog.domain.ShowRepository;
import com.stampedeio.catalog.domain.Venue;
import com.stampedeio.catalog.domain.VenueRepository;

@SpringBootTest(properties = "catalog.projection.enabled=true")
@Testcontainers
class SeatAvailabilityReplayTest {

    private static final String TOPIC = "reservations.events";
    private static final String GROUP_ID = "catalog-availability-projector";

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
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired SeatRepository seatRepository;
    @Autowired VenueRepository venueRepository;
    @Autowired EventRepository eventRepository;
    @Autowired ShowRepository showRepository;
    @Autowired ProcessedEventRepository processedEventRepository;
    @Autowired ProjectionOffsetRepository projectionOffsetRepository;
    @Autowired KafkaListenerEndpointRegistry listenerRegistry;

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

        Venue venue = new Venue("Replay Venue " + UUID.randomUUID(), "2 Test St", 200);
        venueRepository.save(venue);

        Event event = new Event(venue.getId(), "Replay Event " + UUID.randomUUID(), "desc");
        eventRepository.save(event);

        Instant startsAt = Instant.now().plus(2, ChronoUnit.DAYS);
        Show show = new Show(event.getId(), startsAt, startsAt.plus(2, ChronoUnit.HOURS));
        showRepository.save(show);
        showId = show.getId();

        Seat seat1 = new Seat(showId, "VIP", "A", 1, 10000L);
        Seat seat2 = new Seat(showId, "VIP", "A", 2, 10000L);
        seatRepository.save(seat1);
        seatRepository.save(seat2);
        seatId1 = seat1.getId();
        seatId2 = seat2.getId();
    }

    @Test
    void replayFromZero_rebuildsProjectionCorrectly() throws Exception {
        UUID eventId1 = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        Map<String, Object> holdEvent = Map.of(
                "eventId", eventId1.toString(),
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

        UUID eventId2 = UUID.randomUUID();
        Map<String, Object> releaseEvent = Map.of(
                "eventId", eventId2.toString(),
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

        kafkaTemplate.send(TOPIC, reservationId.toString(), holdEvent);
        kafkaTemplate.send(TOPIC, reservationId.toString(), releaseEvent);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(processedEventRepository.existsById(eventId2)).isTrue();
        });

        // Verify initial state: seat1=AVAILABLE (held then released), seat2=HELD
        Seat s1 = seatRepository.findById(seatId1).orElseThrow();
        Seat s2 = seatRepository.findById(seatId2).orElseThrow();
        assertThat(s1.getAvailability()).isEqualTo(Seat.Availability.AVAILABLE);
        assertThat(s2.getAvailability()).isEqualTo(Seat.Availability.HELD);

        // --- Simulate truncate + replay ---
        // Stop the listener
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            container.stop();
        }

        // Truncate projection tables
        processedEventRepository.deleteAll();
        projectionOffsetRepository.deleteAll();

        // Reset seats to AVAILABLE (simulating projection rebuild from scratch)
        seatRepository.findById(seatId1).ifPresent(seat -> {
            seat.setAvailability(Seat.Availability.AVAILABLE);
            seatRepository.save(seat);
        });
        seatRepository.findById(seatId2).ifPresent(seat -> {
            seat.setAvailability(Seat.Availability.AVAILABLE);
            seatRepository.save(seat);
        });

        // Reset consumer group offset to 0
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            TopicPartition tp = new TopicPartition(TOPIC, 0);
            admin.alterConsumerGroupOffsets(GROUP_ID,
                    Map.of(tp, new OffsetAndMetadata(0L))).all().get(10, TimeUnit.SECONDS);
        }

        // Restart the listener
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            container.start();
        }

        // Wait for replay to complete — both events reprocessed
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(processedEventRepository.existsById(eventId1)).isTrue();
            assertThat(processedEventRepository.existsById(eventId2)).isTrue();
        });

        // After replay: seat1 should be AVAILABLE (held then released), seat2 should be HELD
        Seat replayed1 = seatRepository.findById(seatId1).orElseThrow();
        Seat replayed2 = seatRepository.findById(seatId2).orElseThrow();
        assertThat(replayed1.getAvailability()).isEqualTo(Seat.Availability.AVAILABLE);
        assertThat(replayed2.getAvailability()).isEqualTo(Seat.Availability.HELD);
    }
}
