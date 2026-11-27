package com.sachingupta.contactnexus.user.dto;

import java.util.List;

/** {@code token} is null (and {@code enabled} false) until the user turns sharing on. */
public record CardShareResponse(boolean enabled, String token, List<String> fields, List<String> available) {
}
