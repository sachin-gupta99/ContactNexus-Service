package com.sachingupta.contactnexus.contact.dto;

import java.util.List;

/**
 * Contacts that share an email ({@code reason = "email"}) or phone number
 * ({@code reason = "phone"}), oldest first.
 */
public record DuplicateGroupResponse(String reason, String value, List<ContactResponse> contacts) {
}
