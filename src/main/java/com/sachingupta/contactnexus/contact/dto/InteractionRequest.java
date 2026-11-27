package com.sachingupta.contactnexus.contact.dto;

import com.sachingupta.contactnexus.contact.Interaction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** {@code occurredAt} defaults to now; it may be in the past but not the future. */
public record InteractionRequest(
        @NotNull Interaction.Type type,
        @Size(max = 1000) String note,
        Instant occurredAt) {
}
