package com.stampedeio.catalog.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.stampedeio.catalog.config.SecurityConfig;
import com.stampedeio.catalog.domain.Event;
import com.stampedeio.catalog.domain.EventRepository;
import com.stampedeio.catalog.domain.VenueRepository;
import com.stampedeio.catalog.exception.GlobalExceptionHandler;

@WebMvcTest(EventController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class RbacSecurityTest {

    @Autowired MockMvc mvc;
    @MockitoBean EventRepository events;
    @MockitoBean VenueRepository venues;

    private static final UUID ORGANIZER_ID = UUID.randomUUID();
    private static final UUID OTHER_ORGANIZER_ID = UUID.randomUUID();
    private static final UUID VENUE_ID = UUID.randomUUID();

    // --- POST /api/v1/events ---

    @Test
    void postEvent_anonymous_returns401() throws Exception {
        mvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postEvent_userRole_returns403WithProblemJson() throws Exception {
        mvc.perform(post("/api/v1/events")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.claim("user_id", ORGANIZER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @Test
    void postEvent_organizerRole_returns201() throws Exception {
        given(venues.existsById(VENUE_ID)).willReturn(true);
        given(events.existsByVenueIdAndName(VENUE_ID, "Concert")).willReturn(false);
        given(events.save(any(Event.class))).willAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/v1/events")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ORGANIZER"))
                                .jwt(j -> j.claim("user_id", ORGANIZER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody()))
                .andExpect(status().isCreated());
    }

    // --- PUT /api/v1/events/{id} (BOLA) ---

    @Test
    void putEvent_organizerOwnsEvent_returns200() throws Exception {
        Event event = new Event(VENUE_ID, "Concert", "desc", ORGANIZER_ID);
        given(events.findById(event.getId())).willReturn(Optional.of(event));
        given(events.existsByVenueIdAndName(VENUE_ID, "Concert")).willReturn(false);
        given(events.save(any(Event.class))).willAnswer(inv -> inv.getArgument(0));

        mvc.perform(put("/api/v1/events/{id}", event.getId())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ORGANIZER"))
                                .jwt(j -> j.claim("user_id", ORGANIZER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody()))
                .andExpect(status().isOk());
    }

    @Test
    void putEvent_organizerDoesNotOwnEvent_returns403() throws Exception {
        Event event = new Event(VENUE_ID, "Concert", "desc", OTHER_ORGANIZER_ID);
        given(events.findById(event.getId())).willReturn(Optional.of(event));

        mvc.perform(put("/api/v1/events/{id}", event.getId())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ORGANIZER"))
                                .jwt(j -> j.claim("user_id", ORGANIZER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @Test
    void putEvent_userRole_returns403() throws Exception {
        mvc.perform(put("/api/v1/events/{id}", UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.claim("user_id", ORGANIZER_ID.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventBody()))
                .andExpect(status().isForbidden());
    }

    // --- DELETE /api/v1/events/{id} ---

    @Test
    void deleteEvent_adminRole_returns204() throws Exception {
        Event event = new Event(VENUE_ID, "Concert", "desc", ORGANIZER_ID);
        given(events.findById(event.getId())).willReturn(Optional.of(event));

        mvc.perform(delete("/api/v1/events/{id}", event.getId())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteEvent_organizerRole_returns403() throws Exception {
        mvc.perform(delete("/api/v1/events/{id}", UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ORGANIZER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteEvent_userRole_returns403() throws Exception {
        mvc.perform(delete("/api/v1/events/{id}", UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    // --- GET (public) ---

    @Test
    void getEvent_anonymous_returns200() throws Exception {
        Event event = new Event(VENUE_ID, "Concert", "desc");
        given(events.findById(event.getId())).willReturn(Optional.of(event));

        mvc.perform(get("/api/v1/events/{id}", event.getId()))
                .andExpect(status().isOk());
    }

    private String eventBody() {
        return """
                {"venueId":"%s","name":"Concert","description":"desc"}
                """.formatted(VENUE_ID);
    }
}
