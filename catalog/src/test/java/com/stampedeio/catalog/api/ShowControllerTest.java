package com.stampedeio.catalog.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.stampedeio.catalog.domain.EventRepository;
import com.stampedeio.catalog.domain.Show;
import com.stampedeio.catalog.domain.ShowRepository;
import com.stampedeio.catalog.exception.GlobalExceptionHandler;

@WebMvcTest(ShowController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ShowControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ShowRepository shows;
    @MockitoBean EventRepository events;

    @Test
    void create_returns201() throws Exception {
        UUID eventId = UUID.randomUUID();
        Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS);
        Instant endsAt = startsAt.plus(2, ChronoUnit.HOURS);
        given(events.existsById(eventId)).willReturn(true);
        given(shows.save(any(Show.class))).willAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/v1/shows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"%s","startsAt":"%s","endsAt":"%s"}
                                """.formatted(eventId, startsAt, endsAt)))
                .andExpect(status().isCreated());
    }

    @Test
    void create_showInPast_returns400() throws Exception {
        UUID eventId = UUID.randomUUID();
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        given(events.existsById(eventId)).willReturn(true);

        mvc.perform(post("/api/v1/shows")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"%s","startsAt":"%s","endsAt":"%s"}
                                """.formatted(eventId, past, past.plus(1, ChronoUnit.HOURS))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("in the past")));
    }

    @Test
    void get_missing_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        given(shows.findById(id)).willReturn(Optional.empty());

        mvc.perform(get("/api/v1/shows/{id}", id))
                .andExpect(status().isNotFound());
    }
}
