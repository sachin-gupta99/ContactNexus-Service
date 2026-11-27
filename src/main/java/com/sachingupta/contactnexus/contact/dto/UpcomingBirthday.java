package com.sachingupta.contactnexus.contact.dto;

import java.time.LocalDate;

/** {@code turning} is null when the birth year isn't known. */
public record UpcomingBirthday(ContactResponse contact, LocalDate date, long inDays, Integer turning) {
}
