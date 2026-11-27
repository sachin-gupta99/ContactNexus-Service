package com.sachingupta.contactnexus.contact.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** {@code tags} is required for ADD_TAGS / REMOVE_TAGS and ignored otherwise. */
public record BulkRequest(
        @NotEmpty @Size(max = 500) List<@NotNull Long> ids,
        @NotNull Action action,
        @Size(max = 10) List<@Size(max = 30) String> tags) {

    public enum Action { FAVORITE, UNFAVORITE, ADD_TAGS, REMOVE_TAGS, DELETE }
}
