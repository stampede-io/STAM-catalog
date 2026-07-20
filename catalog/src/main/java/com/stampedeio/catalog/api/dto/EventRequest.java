package com.stampedeio.catalog.api.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EventRequest(
        @NotNull UUID venueId,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 5000) String description) {}
