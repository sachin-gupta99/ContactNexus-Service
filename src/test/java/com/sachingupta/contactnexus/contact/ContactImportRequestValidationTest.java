package com.sachingupta.contactnexus.contact;

import com.sachingupta.contactnexus.contact.dto.ContactImportRequest;
import com.sachingupta.contactnexus.contact.dto.ContactRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ContactImportRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static ContactRequest contact(String firstName, String email) {
        return new ContactRequest(firstName, null, email, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    private Set<String> violationPaths(ContactImportRequest req) {
        Set<ConstraintViolation<ContactImportRequest>> v = validator.validate(req);
        return v.stream().map(c -> c.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void acceptsAValidBatch() {
        assertThat(violationPaths(new ContactImportRequest(List.of(contact("Ann", "ann@x.com"))))).isEmpty();
    }

    @Test
    void rejectsEmptyAndOversizedBatches() {
        assertThat(violationPaths(new ContactImportRequest(List.of()))).containsExactly("contacts");
        List<ContactRequest> tooMany = Collections.nCopies(ContactImportRequest.MAX_BATCH + 1, contact("A", "a@x.com"));
        assertThat(violationPaths(new ContactImportRequest(tooMany))).containsExactly("contacts");
    }

    @Test
    void reportsTheIndexOfAnInvalidRow() {
        ContactImportRequest req = new ContactImportRequest(List.of(
                contact("Ann", "ann@x.com"),
                contact("Bob", "not-an-email")));
        assertThat(violationPaths(req)).containsExactly("contacts[1].email");
    }

    @Test
    void rejectsValuesLongerThanTheColumn() {
        ContactImportRequest req = new ContactImportRequest(List.of(contact("x".repeat(256), "ann@x.com")));
        assertThat(violationPaths(req)).containsExactly("contacts[0].firstName");
    }
}
