package com.sachingupta.contactnexus.contact.dto;

import com.sachingupta.contactnexus.contact.Interaction;

import java.time.Instant;

public record InteractionResponse(Long id, Interaction.Type type, String note, Instant occurredAt, Instant createdAt) {

    public static InteractionResponse from(Interaction i) {
        return new InteractionResponse(i.getId(), i.getType(), i.getNote(), i.getOccurredAt(), i.getCreatedAt());
    }
}
