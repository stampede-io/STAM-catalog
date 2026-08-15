package com.stampedeio.catalog.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
class EventControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean EventRepository events;
    @MockitoBean VenueRepository venues;

    @Test
    void create_returns201WithLocation() throws Exception {
        UUID venueId = UUID.randomUUID();
        given(venues.existsById(venueId)).willReturn(true);
        given(events.existsByVenueIdAndName(venueId, "Concert")).willReturn(false);
        given(events.save(any(Event.class))).willAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/v1/events")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ORGANIZER"))
                                .jwt(j -> j.claim("user_id", UUID.randomUUID().toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"venueId":"%s","name":"Concert","description":"desc"}
                                """.formatted(venueId)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.name").value("Concert"));
    }

    @Test
    void create_invalidPayload_returns400() throws Exception {
        mvc.perform(post("/api/v1/events")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ORGANIZER"))
                                .jwt(j -> j.claim("user_id", UUID.randomUUID().toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void create_duplicateName_returns409() throws Exception {
        UUID venueId = UUID.randomUUID();
        given(venues.existsById(venueId)).willReturn(true);
        given(events.existsByVenueIdAndName(venueId, "Dup")).willReturn(true);

        mvc.perform(post("/api/v1/events")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ORGANIZER"))
                                .jwt(j -> j.claim("user_id", UUID.randomUUID().toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"venueId":"%s","name":"Dup"}
                                """.formatted(venueId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"));
    }

    @Test
    void create_missingVenue_returns404() throws Exception {
        UUID venueId = UUID.randomUUID();
        given(venues.existsById(venueId)).willReturn(false);

        mvc.perform(post("/api/v1/events")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ORGANIZER"))
                                .jwt(j -> j.claim("user_id", UUID.randomUUID().toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"venueId":"%s","name":"Concert"}
                                """.formatted(venueId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_existing_returns200() throws Exception {
        Event e = new Event(UUID.randomUUID(), "Concert", null);
        given(events.findById(e.getId())).willReturn(Optional.of(e));

        mvc.perform(get("/api/v1/events/{id}", e.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Concert"));
    }
}
