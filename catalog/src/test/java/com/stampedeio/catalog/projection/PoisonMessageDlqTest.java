package com.stampedeio.catalog.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
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

@SpringBootTest(properties = {
        "catalog.projection.enabled=true",
        "catalog.kafka.retry.initial-interval=200",
        "catalog.kafka.retry.multiplier=2.0",
        "catalog.kafka.retry.max-attempts=3"
})
@Testcontainers
class PoisonMessageDlqTest {

    private static final String TOPIC = "reservations.events";
    private static final String DLQ_TOPIC = "reservations.events.dlq";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired SeatRepository seatRepository;
    @Autowired VenueRepository venueRepository;
    @Autowired EventRepository eventRepository;
    @Autowired ShowRepository showRepository;
    @Autowired ProcessedEventRepository processedEventRepository;

    private KafkaTemplate<String, Object> kafkaTemplate;
    private UUID showId;
    private UUID seatId1;

    @BeforeEach
    void setUp() {
        Map<String, Object> producerProps = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        kafkaTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));

        Venue venue = new Venue("DLQ Venue " + UUID.randomUUID(), "3 Test St", 50);
        venueRepository.save(venue);

        Event event = new Event(venue.getId(), "DLQ Event " + UUID.randomUUID(), "desc");
        eventRepository.save(event);

        Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS);
        Show show = new Show(event.getId(), startsAt, startsAt.plus(2, ChronoUnit.HOURS));
        showRepository.save(show);
        showId = show.getId();

        Seat seat1 = new Seat(showId, "GA", "B", 1, 3000L);
        seatRepository.save(seat1);
        seatId1 = seat1.getId();
    }

    @Test
    void poisonMessage_routedToDlq_withFailureHeaders_subsequentMessagesStillProcessed() {
        // AC4: Send a poison message (missing required fields → NonRetryableProjectionException)
        Map<String, Object> poisonEnvelope = Map.of(
                "garbage", "this has no eventId or eventType");

        kafkaTemplate.send(TOPIC, "poison-key", poisonEnvelope);

        // Then send a valid message after the poison
        UUID validEventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        Map<String, Object> validEnvelope = Map.of(
                "eventId", validEventId.toString(),
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

        kafkaTemplate.send(TOPIC, reservationId.toString(), validEnvelope);

        // AC4: Valid message should still be processed despite the poison message
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(processedEventRepository.existsById(validEventId)).isTrue();
            Seat s1 = seatRepository.findById(seatId1).orElseThrow();
            assertThat(s1.getAvailability()).isEqualTo(Seat.Availability.HELD);
        });

        // AC1: Verify the poison message ended up in the DLQ with correct headers
        List<ConsumerRecord<String, byte[]>> dlqRecords = consumeDlqRecords();
        assertThat(dlqRecords).isNotEmpty();

        ConsumerRecord<String, byte[]> dlqRecord = dlqRecords.get(0);
        assertThat(headerValue(dlqRecord, "original-topic")).isEqualTo(TOPIC);
        assertThat(headerValue(dlqRecord, "exception-class")).isNotEmpty();
        assertThat(headerValue(dlqRecord, "exception-message")).isNotEmpty();
        assertThat(headerValue(dlqRecord, "stack-trace")).isNotEmpty();
    }

    private List<ConsumerRecord<String, byte[]>> consumeDlqRecords() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlq-test-consumer-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                        org.apache.kafka.common.serialization.ByteArrayDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(DLQ_TOPIC));
            List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();

            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, byte[]> polled = consumer.poll(Duration.ofMillis(500));
                polled.forEach(records::add);
                if (!records.isEmpty()) break;
            }
            return records;
        }
    }

    private String headerValue(ConsumerRecord<?, ?> record, String key) {
        Header header = record.headers().lastHeader(key);
        return header != null ? new String(header.value(), StandardCharsets.UTF_8) : null;
    }
}
