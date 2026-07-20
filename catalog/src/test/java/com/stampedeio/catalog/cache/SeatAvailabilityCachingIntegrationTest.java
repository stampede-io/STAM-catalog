package com.stampedeio.catalog.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
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

/**
 * Verifies the STAM-29 acceptance criteria for Redis-cached availability
 * reads end to end over the real REST API, using a real Postgres and Redis
 * via Testcontainers.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "catalog.projection.enabled=false",
        "catalog.cache.availability-ttl-seconds=2",
        "catalog.cache.lock-wait-timeout-ms=3000",
        "catalog.cache.lock-poll-interval-ms=10"
})
@AutoConfigureTestRestTemplate
@Testcontainers
class SeatAvailabilityCachingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    VenueRepository venueRepository;

    @Autowired
    EventRepository eventRepository;

    @Autowired
    ShowRepository showRepository;

    @MockitoSpyBean
    SeatRepository seatRepository;

    private java.util.UUID seedShow(int seatCount) {
        Venue venue = venueRepository.save(new Venue("Cache Test Venue " + java.util.UUID.randomUUID(), "1 Cache St", 500));
        Event event = eventRepository.save(new Event(venue.getId(), "Cache Test Event", "desc"));
        Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS);
        Show show = showRepository.save(new Show(event.getId(), startsAt, startsAt.plus(2, ChronoUnit.HOURS)));
        for (int i = 1; i <= seatCount; i++) {
            seatRepository.save(new Seat(show.getId(), "GA", "A", i, 5000L));
        }
        return show.getId();
    }

    // AC1: a cache hit is served from Redis without querying Postgres.
    @Test
    void cacheHit_doesNotQueryDatabase() {
        var showId = seedShow(5);

        ResponseEntity<List> first = rest.getForEntity("/api/v1/shows/{id}/seats", List.class, showId);
        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(first.getBody()).hasSize(5);

        ResponseEntity<List> second = rest.getForEntity("/api/v1/shows/{id}/seats", List.class, showId);
        assertThat(second.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(second.getBody()).isEqualTo(first.getBody());

        verify(seatRepository, times(1)).findByShowIdOrderBySectionAscRowLabelAscSeatNumberAsc(showId);
    }

    // AC3: 100 concurrent requests on a cache miss trigger exactly one DB query.
    @Test
    void concurrentMiss_singleFlightProtectsDatabase() throws Exception {
        var showId = seedShow(3);

        int concurrency = 100;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();

        try {
            for (int i = 0; i < concurrency; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        ResponseEntity<List> resp = rest.getForEntity("/api/v1/shows/{id}/seats", List.class, showId);
                        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null || resp.getBody().size() != 3) {
                            failures.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                });
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertThat(failures.get()).isZero();
        verify(seatRepository, times(1)).findByShowIdOrderBySectionAscRowLabelAscSeatNumberAsc(showId);
    }

    // AC5: TTL safety net auto-refreshes the cache without an invalidation event.
    @Test
    void ttlExpiry_refreshesCacheWithoutInvalidationEvent() {
        var showId = seedShow(2);

        rest.getForEntity("/api/v1/shows/{id}/seats", List.class, showId);
        verify(seatRepository, times(1)).findByShowIdOrderBySectionAscRowLabelAscSeatNumberAsc(showId);

        await().atMost(5, TimeUnit.SECONDS).pollDelay(2200, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            rest.getForEntity("/api/v1/shows/{id}/seats", List.class, showId);
            verify(seatRepository, times(2)).findByShowIdOrderBySectionAscRowLabelAscSeatNumberAsc(showId);
        });
    }

    // AC4: cache_hits / cache_misses counters, tagged cacheName=availability, are exported.
    @Test
    void metrics_exposeHitAndMissCountersWithCacheNameTag() {
        var showId = seedShow(1);

        rest.getForEntity("/api/v1/shows/{id}/seats", List.class, showId); // miss
        rest.getForEntity("/api/v1/shows/{id}/seats", List.class, showId); // hit

        ResponseEntity<String> metrics = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(metrics.getStatusCode().is2xxSuccessful()).isTrue();
        String body = metrics.getBody();
        assertThat(body).contains("cache_hits_total");
        assertThat(body).contains("cache_misses_total");
        assertThat(body).containsPattern("cache_hits_total\\{[^}]*cacheName=\"availability\"[^}]*}");
        assertThat(body).containsPattern("cache_misses_total\\{[^}]*cacheName=\"availability\"[^}]*}");
    }
}
