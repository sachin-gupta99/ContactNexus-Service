package com.sachingupta.contactnexus.contact.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Merge {@code mergeIds} into {@code keepId}. {@code values} are the field values
 * the user picked (validated like a normal update); {@code values.tags == null}
 * means "union of everyone's tags". {@code photoFromId} picks whose photo to keep;
 * null keeps the kept contact's photo, or the first merged one that has a photo.
 */
public record MergeRequest(
        @NotNull Long keepId,
        @NotEmpty @Size(max = 20) List<@NotNull Long> mergeIds,
        @NotNull @Valid ContactRequest values,
        Long photoFromId) {
}
