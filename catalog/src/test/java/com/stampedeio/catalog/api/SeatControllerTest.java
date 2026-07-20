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

import com.stampedeio.catalog.api.dto.SeatResponse;
import com.stampedeio.catalog.cache.SeatAvailabilityCacheService;
import com.stampedeio.catalog.domain.SeatRepository;
import com.stampedeio.catalog.exception.GlobalExceptionHandler;
import com.stampedeio.catalog.exception.ResourceNotFoundException;

@WebMvcTest(SeatController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class SeatControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean SeatAvailabilityCacheService availability;
    @MockitoBean SeatRepository seats;

    @Test
    void listSeats_returnsSeatsForExistingShow() throws Exception {
        UUID showId = UUID.randomUUID();
        SeatResponse seat = new SeatResponse(UUID.randomUUID(), showId, "GA", "A", 1, 1000L, "AVAILABLE");
        given(availability.getSeats(showId)).willReturn(List.of(seat));

        mvc.perform(get("/api/v1/shows/{id}/seats", showId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].section").value("GA"))
                .andExpect(jsonPath("$[0].availability").value("AVAILABLE"));
    }

    @Test
    void listSeats_missingShow_returns404() throws Exception {
        UUID showId = UUID.randomUUID();
        given(availability.getSeats(showId)).willThrow(new ResourceNotFoundException("Show", showId));

        mvc.perform(get("/api/v1/shows/{id}/seats", showId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void validateSeats_allBelongToShow_returns204() throws Exception {
        UUID showId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        given(seats.countByShowIdAndIdIn(showId, List.of(seatId))).willReturn(1L);

        mvc.perform(get("/api/v1/shows/{id}/seats/validate", showId).param("ids", seatId.toString()))
                .andExpect(status().isNoContent());
    }

    @Test
    void validateSeats_someMissing_returns404() throws Exception {
        UUID showId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        given(seats.countByShowIdAndIdIn(showId, List.of(seatId))).willReturn(0L);

        mvc.perform(get("/api/v1/shows/{id}/seats/validate", showId).param("ids", seatId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
