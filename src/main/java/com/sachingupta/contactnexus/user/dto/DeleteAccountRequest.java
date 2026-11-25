package com.sachingupta.contactnexus.user.dto;

import jakarta.validation.constraints.NotBlank;

/** The password is re-checked so a stolen access token alone can't delete the account. */
public record DeleteAccountRequest(@NotBlank String password) {
}
