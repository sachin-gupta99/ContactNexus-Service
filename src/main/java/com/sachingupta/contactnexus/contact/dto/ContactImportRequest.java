package com.sachingupta.contactnexus.contact.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A batch parsed client-side from a .vcf or .csv file. Each entry is validated
 * like a normal create, so one bad row rejects the batch with a field path such
 * as {@code contacts[3].email}; the UI filters invalid rows out before sending.
 */
public record ContactImportRequest(
        @NotEmpty @Size(max = ContactImportRequest.MAX_BATCH) List<@Valid ContactRequest> contacts) {

    public static final int MAX_BATCH = 500;
}
