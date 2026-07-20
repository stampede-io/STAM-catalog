package com.stampedeio.catalog.api.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record ShowRequest(
        @NotNull UUID eventId,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt) {}
