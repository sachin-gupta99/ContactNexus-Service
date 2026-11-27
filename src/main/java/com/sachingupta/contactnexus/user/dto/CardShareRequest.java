package com.sachingupta.contactnexus.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Which fields the public card shows. The name is always shown. */
public record CardShareRequest(@NotNull @Size(max = 20) List<@NotNull String> fields) {
}
