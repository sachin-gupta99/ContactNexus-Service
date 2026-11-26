package com.sachingupta.contactnexus.contact;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional
public class TagService {

    private final TagRepository tags;

    /**
     * Trims, collapses inner whitespace, drops blanks, and de-duplicates ignoring
     * case (keeping the first spelling). Order is preserved.
     */
    public static List<String> normalize(Collection<String> raw) {
        if (raw == null) {
            return List.of();
        }
        Map<String, String> byLower = new LinkedHashMap<>();
        for (String name : raw) {
            if (name == null) continue;
            String clean = name.trim().replaceAll("\\s+", " ");
            if (!clean.isEmpty()) {
                byLower.putIfAbsent(clean.toLowerCase(Locale.ROOT), clean);
            }
        }
        return List.copyOf(byLower.values());
    }

    /**
     * Finds or creates the owner's tags for these (already normalized) names.
     * @return lower-cased name to tag
     */
    public Map<String, Tag> resolve(Long ownerId, Collection<String> names) {
        Map<String, Tag> result = new HashMap<>();
        if (names.isEmpty()) {
            return result;
        }
        Map<String, String> wanted = new LinkedHashMap<>();
        names.forEach(n -> wanted.putIfAbsent(n.toLowerCase(Locale.ROOT), n));

        for (Tag t : tags.findByOwnerAndLowerNames(ownerId, wanted.keySet())) {
            result.put(t.getName().toLowerCase(Locale.ROOT), t);
        }
        wanted.forEach((lower, name) -> result.computeIfAbsent(lower,
                k -> tags.save(Tag.builder().ownerId(ownerId).name(name).build())));
        return result;
    }

    public void deleteUnused(Long ownerId) {
        tags.deleteUnused(ownerId);
    }
}
