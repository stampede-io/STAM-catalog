package com.stampedeio.catalog.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.stampedeio.catalog.domain.Venue;
import com.stampedeio.catalog.domain.VenueRepository;
import com.stampedeio.catalog.exception.GlobalExceptionHandler;

@WebMvcTest(VenueController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class VenueControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean VenueRepository venues;

    @Test
    void create_returns201WithLocation() throws Exception {
        given(venues.existsByName("Test")).willReturn(false);
        given(venues.save(any(Venue.class))).willAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/v1/venues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Test","address":"1 St","capacity":100}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.name").value("Test"))
                .andExpect(jsonPath("$.capacity").value(100));
    }

    @Test
    void create_invalidPayload_returns400() throws Exception {
        mvc.perform(post("/api/v1/venues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","address":"1 St","capacity":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Bad Request"));
    }

    @Test
    void create_duplicateName_returns409() throws Exception {
        given(venues.existsByName("Dup")).willReturn(true);

        mvc.perform(post("/api/v1/venues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Dup","address":"1 St","capacity":100}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Conflict"));
    }

    @Test
    void get_missing_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        given(venues.findById(id)).willReturn(Optional.empty());

        mvc.perform(get("/api/v1/venues/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void get_existing_returns200() throws Exception {
        Venue v = new Venue("V", "addr", 10);
        given(venues.findById(v.getId())).willReturn(Optional.of(v));

        mvc.perform(get("/api/v1/venues/{id}", v.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("V"));
    }

    @Test
    void list_returnsNextCursorWhenPageFull() throws Exception {
        Venue a = new Venue("A", "addr", 10);
        Venue b = new Venue("B", "addr", 10);
        given(venues.findPage(null, Limit.of(2))).willReturn(List.of(a, b));

        mvc.perform(get("/api/v1/venues").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").value(b.getId().toString()));
    }

    @Test
    void list_nextCursorNullOnPartialPage() throws Exception {
        Venue a = new Venue("A", "addr", 10);
        given(venues.findPage(null, Limit.of(5))).willReturn(List.of(a));

        mvc.perform(get("/api/v1/venues").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }
}
