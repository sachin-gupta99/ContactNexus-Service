package com.sachingupta.contactnexus.contact.dto;

/** {@code tags} is how many distinct tags (groups) the owner uses. */
public record ContactStats(long total, long favorites, long addedThisWeek, long tags) {
}
