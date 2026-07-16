package com.stampedeio.catalog.api;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.stampedeio.catalog.domain.Seat;
import com.stampedeio.catalog.domain.SeatRepository;
import com.stampedeio.catalog.domain.ShowRepository;
import com.stampedeio.catalog.exception.GlobalExceptionHandler;

@WebMvcTest(SeatController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class SeatControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean SeatRepository seats;
    @MockitoBean ShowRepository shows;

    @Test
    void listSeats_returnsSeatsForExistingShow() throws Exception {
        UUID showId = UUID.randomUUID();
        given(shows.existsById(showId)).willReturn(true);
        Seat seat = new Seat(showId, "GA", "A", 1, 1000L);
        given(seats.findByShowIdOrderBySectionAscRowLabelAscSeatNumberAsc(showId)).willReturn(List.of(seat));

        mvc.perform(get("/api/v1/shows/{id}/seats", showId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].section").value("GA"))
                .andExpect(jsonPath("$[0].availability").value("AVAILABLE"));
    }

    @Test
    void listSeats_missingShow_returns404() throws Exception {
        UUID showId = UUID.randomUUID();
        given(shows.existsById(showId)).willReturn(false);

        mvc.perform(get("/api/v1/shows/{id}/seats", showId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
