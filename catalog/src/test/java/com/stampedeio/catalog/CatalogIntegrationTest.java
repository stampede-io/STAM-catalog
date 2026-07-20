package com.stampedeio.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import com.stampedeio.catalog.domain.Seat;
import com.stampedeio.catalog.domain.SeatRepository;

/**
 * End-to-end integration test for the catalog service.
 *
 * <p>Spins up a real PostgreSQL 16 container via Testcontainers and wires it
 * into Spring Boot with {@link ServiceConnection}, so Flyway runs the real
 * V2 migration before the test starts and the full application context
 * (security, JPA, controllers) is exercised over HTTP with
 * {@link TestRestTemplate}. No profile is activated, so the main
 * application.yml (Postgres + Flyway) drives the app while
 * {@link ServiceConnection} redirects the datasource to the container.
 * The H2-backed <code>slice</code> profile used by other tests stays
 * dormant here.
 *
 * <p>Venue, event, and show are created through the REST layer, matching
 * STAM-18 AC2. Seats are persisted directly via {@link SeatRepository}
 * rather than REST: the catalog service does not yet expose a seat-creation
 * endpoint (seats are currently provisioned only by the dev-profile
 * {@code DataLoader} seed script). This still exercises the real read path
 * this test cares about — {@code GET /api/v1/shows/{showId}/seats}
 * returning the correct count — without inventing an unreviewed write API
 * as a side effect of this story.
 *
 * <p><b>Local runtime:</b> ~15 seconds warm (Testcontainers reuse enabled in
 * <code>~/.testcontainers.properties</code>), ~30 seconds cold. Well within
 * the 90 s ceiling required by STAM-18 AC3.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = "catalog.projection.enabled=false")
@AutoConfigureTestRestTemplate
@org.testcontainers.junit.jupiter.Testcontainers
class CatalogIntegrationTest {

    @org.testcontainers.junit.jupiter.Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"));

    @org.testcontainers.junit.jupiter.Container
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
    SeatRepository seats;

    @Value("${jwt.hmac-secret}")
    String hmacSecret;

    @Test
    void venueEventShowSeats_roundTripsThroughRestApi() throws Exception {
        HttpHeaders organizer = organizerHeaders();

        String venueName = "IT Arena " + UUID.randomUUID();
        Map<String, Object> venueBody = Map.of(
                "name", venueName,
                "address", "1 Integration St",
                "capacity", 500);
        ResponseEntity<Map> venueResp = rest.exchange(
                "/api/v1/venues", HttpMethod.POST,
                new HttpEntity<>(venueBody, organizer), Map.class);
        assertThat(venueResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(venueResp.getHeaders().getLocation()).isNotNull();
        UUID venueId = UUID.fromString((String) venueResp.getBody().get("id"));

        Map<String, Object> eventBody = Map.of(
                "venueId", venueId.toString(),
                "name", "IT Concert",
                "description", "wired via integration test");
        ResponseEntity<Map> eventResp = rest.exchange(
                "/api/v1/events", HttpMethod.POST,
                new HttpEntity<>(eventBody, organizer), Map.class);
        assertThat(eventResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID eventId = UUID.fromString((String) eventResp.getBody().get("id"));

        Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Instant endsAt = startsAt.plus(2, ChronoUnit.HOURS);
        Map<String, Object> showBody = Map.of(
                "eventId", eventId.toString(),
                "startsAt", startsAt.toString(),
                "endsAt", endsAt.toString());
        ResponseEntity<Map> showResp = rest.exchange(
                "/api/v1/shows", HttpMethod.POST,
                new HttpEntity<>(showBody, organizer), Map.class);
        assertThat(showResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID showId = UUID.fromString((String) showResp.getBody().get("id"));

        int seatCount = 12;
        for (int i = 1; i <= seatCount; i++) {
            seats.save(new Seat(showId, "GA", "A", i, 5000L));
        }

        ResponseEntity<List> seatsResp = rest.getForEntity(
                "/api/v1/shows/{id}/seats", List.class, showId);
        assertThat(seatsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(seatsResp.getBody()).hasSize(seatCount);

        ResponseEntity<Map> venueLookup = rest.getForEntity(
                "/api/v1/venues/{id}", Map.class, venueId);
        assertThat(venueLookup.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(venueLookup.getBody()).containsEntry("name", venueName);
    }

    private HttpHeaders organizerHeaders() throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                new JWTClaimsSet.Builder()
                        .subject("integration-test-user")
                        .claim("roles", List.of("ORGANIZER"))
                        .issueTime(Date.from(Instant.now()))
                        .expirationTime(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)))
                        .build());
        jwt.sign(new MACSigner(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwt.serialize());
        return headers;
    }
}
