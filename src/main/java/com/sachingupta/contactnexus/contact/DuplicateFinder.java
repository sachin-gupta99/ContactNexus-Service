package com.sachingupta.contactnexus.contact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Groups contacts that are probably the same person: same email (ignoring case
 * and surrounding spaces) or same phone number (digits only, compared on the
 * last 10 so "+91 98765 43210" matches "098765-43210"). Numbers with fewer than
 * 7 digits are ignored; they are too short to mean anything.
 */
public final class DuplicateFinder {

    static final int MIN_PHONE_DIGITS = 7;
    static final int PHONE_SUFFIX_DIGITS = 10;

    private DuplicateFinder() {
    }

    public record Group(String reason, String value, List<Contact> contacts) {}

    public static List<Group> find(List<Contact> contacts) {
        List<Group> groups = new ArrayList<>();
        Set<Set<Long>> seen = new HashSet<>();

        collect(contacts, DuplicateFinder::emailKey, "email", groups, seen);
        collect(contacts, DuplicateFinder::phoneKey, "phone", groups, seen);

        groups.sort(Comparator.comparing(g -> displayName(g.contacts().get(0)), String.CASE_INSENSITIVE_ORDER));
        return groups;
    }

    private static void collect(List<Contact> contacts, java.util.function.Function<Contact, String> keyFn,
                                String reason, List<Group> out, Set<Set<Long>> seen) {
        Map<String, List<Contact>> byKey = new LinkedHashMap<>();
        for (Contact c : contacts) {
            String key = keyFn.apply(c);
            if (key != null) {
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
            }
        }
        byKey.forEach((key, members) -> {
            if (members.size() < 2) return;
            // The same people matching on both email and phone are one group, not two.
            Set<Long> ids = members.stream().map(Contact::getId).collect(Collectors.toSet());
            if (seen.add(ids)) {
                List<Contact> sorted = new ArrayList<>(members);
                sorted.sort(Comparator.comparing(Contact::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
                out.add(new Group(reason, displayValue(reason, sorted.get(0)), sorted));
            }
        });
    }

    static String emailKey(Contact c) {
        if (c.getEmail() == null || c.getEmail().isBlank()) return null;
        return c.getEmail().trim().toLowerCase(Locale.ROOT);
    }

    static String phoneKey(Contact c) {
        if (c.getPhone() == null) return null;
        String digits = c.getPhone().replaceAll("\\D", "");
        if (digits.length() < MIN_PHONE_DIGITS) return null;
        return digits.length() > PHONE_SUFFIX_DIGITS ? digits.substring(digits.length() - PHONE_SUFFIX_DIGITS) : digits;
    }

    private static String displayValue(String reason, Contact c) {
        return "email".equals(reason) ? c.getEmail().trim() : c.getPhone().trim();
    }

    private static String displayName(Contact c) {
        String last = c.getLastName() == null ? "" : " " + c.getLastName();
        return (c.getFirstName() + last).trim();
    }
}
