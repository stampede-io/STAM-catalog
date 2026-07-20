package com.stampedeio.catalog.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.stampedeio.catalog.config.SecurityConfig;
import com.stampedeio.catalog.domain.Venue;
import com.stampedeio.catalog.domain.VenueRepository;
import com.stampedeio.catalog.exception.GlobalExceptionHandler;

@WebMvcTest(VenueController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class VenueSecurityTest {

    @Autowired MockMvc mvc;
    @MockitoBean VenueRepository venues;

    private static final String BODY = """
            {"name":"Test","address":"1 St","capacity":100}
            """;

    @Test
    void anonymousPost_returns401() throws Exception {
        mvc.perform(post("/api/v1/venues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postWithoutOrganizerRole_returns403() throws Exception {
        mvc.perform(post("/api/v1/venues")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void postWithOrganizerRole_returns201() throws Exception {
        given(venues.existsByName("Test")).willReturn(false);
        given(venues.save(any(Venue.class))).willAnswer(inv -> inv.getArgument(0));

        mvc.perform(post("/api/v1/venues")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ORGANIZER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"));
    }
}
