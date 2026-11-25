package com.sachingupta.contactnexus.contact.dto;

import jakarta.validation.constraints.NotNull;

public record FavoriteRequest(@NotNull Boolean favorite) {
}
