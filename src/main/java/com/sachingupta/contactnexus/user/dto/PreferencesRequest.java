package com.sachingupta.contactnexus.user.dto;

import jakarta.validation.constraints.NotNull;

public record PreferencesRequest(@NotNull Boolean birthdayReminders) {
}
