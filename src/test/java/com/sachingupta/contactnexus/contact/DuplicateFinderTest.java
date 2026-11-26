package com.sachingupta.contactnexus.contact;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DuplicateFinderTest {

    private static long nextId = 1;

    private static Contact contact(String first, String email, String phone, int createdDaysAgo) {
        Contact c = Contact.builder()
                .id(nextId++).ownerId(1L).firstName(first).email(email).phone(phone)
                .build();
        c.setCreatedAt(Instant.parse("2026-01-31T00:00:00Z").minusSeconds(createdDaysAgo * 86_400L));
        return c;
    }

    private static List<String> names(DuplicateFinder.Group g) {
        return g.contacts().stream().map(Contact::getFirstName).toList();
    }

    @Test
    void groupsSameEmailIgnoringCaseAndSpaces() {
        List<DuplicateFinder.Group> groups = DuplicateFinder.find(List.of(
                contact("Ann", "ann@x.com", null, 1),
                contact("Ann B", " ANN@x.com ", null, 5),
                contact("Bob", "bob@x.com", null, 2)));
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).reason()).isEqualTo("email");
        assertThat(names(groups.get(0))).containsExactly("Ann B", "Ann"); // oldest first
    }

    @Test
    void groupsPhonesByTheirLastTenDigits() {
        List<DuplicateFinder.Group> groups = DuplicateFinder.find(List.of(
                contact("Ravi", "r1@x.com", "+91 98765 43210", 3),
                contact("Ravi K", "r2@x.com", "098765-43210", 2),
                contact("Other", "o@x.com", "98765 43211", 1)));
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).reason()).isEqualTo("phone");
        assertThat(groups.get(0).value()).isEqualTo("+91 98765 43210");
        assertThat(names(groups.get(0))).containsExactly("Ravi", "Ravi K");
    }

    @Test
    void ignoresShortPhoneNumbers() {
        assertThat(DuplicateFinder.find(List.of(
                contact("A", "a@x.com", "123", 1),
                contact("B", "b@x.com", "1-2-3", 2)))).isEmpty();
    }

    @Test
    void sameContactsMatchingOnEmailAndPhoneAreOneGroup() {
        List<DuplicateFinder.Group> groups = DuplicateFinder.find(List.of(
                contact("Zoe", "zoe@x.com", "555 123 4567", 1),
                contact("Zoe L", "zoe@x.com", "(555) 123-4567", 2)));
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).reason()).isEqualTo("email");
    }

    @Test
    void noDuplicatesNoGroups() {
        assertThat(DuplicateFinder.find(List.of(
                contact("A", "a@x.com", "5551234567", 1),
                contact("B", "b@x.com", "5559876543", 2)))).isEmpty();
    }
}
