package com.sachingupta.contactnexus.contact.dto;

/** How many of the requested contacts belonged to the caller and were changed. */
public record BulkResult(int affected) {
}
