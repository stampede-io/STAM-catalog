package com.stampedeio.catalog.api;

import java.util.UUID;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stampedeio.catalog.exception.ResourceNotFoundException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/shows")
@Tag(name = "Shows", description = "Show queries")
public class ShowController {

    @GetMapping("/{id}")
    @Operation(summary = "Get a show by ID")
    @ApiResponse(responseCode = "200", description = "Show found")
    @ApiResponse(responseCode = "404", description = "Show not found",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public ShowResponse getShow(@PathVariable UUID id) {
        // TODO: delegate to show service (STAM-catalog CQRS story)
        throw new ResourceNotFoundException("Show", id);
    }

    public record ShowResponse(
            UUID id,
            String name,
            String venue) {
    }
}
