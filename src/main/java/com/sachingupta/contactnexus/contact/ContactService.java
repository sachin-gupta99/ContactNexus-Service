package com.sachingupta.contactnexus.contact;

import com.sachingupta.contactnexus.common.NotFoundException;
import com.sachingupta.contactnexus.contact.dto.ContactImportResult;
import com.sachingupta.contactnexus.contact.dto.ContactRequest;
import com.sachingupta.contactnexus.contact.dto.ContactStats;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class ContactService {

    private final ContactRepository repository;

    @Transactional(readOnly = true)
    public Page<Contact> list(Long ownerId, String query, boolean favoritesOnly, Pageable pageable) {
        return repository.search(ownerId, likePattern(query), favoritesOnly, pageable);
    }

    public Contact setFavorite(Long ownerId, Long id, boolean favorite) {
        Contact contact = get(ownerId, id);
        contact.setFavorite(favorite);
        return contact;
    }

    @Transactional(readOnly = true)
    public ContactStats stats(Long ownerId) {
        return new ContactStats(
                repository.countByOwnerId(ownerId),
                repository.countByOwnerIdAndFavoriteTrue(ownerId),
                repository.countByOwnerIdAndCreatedAtAfter(ownerId, Instant.now().minus(7, ChronoUnit.DAYS)));
    }

    /** Case-insensitive "contains" pattern; % and _ typed by the user match literally. */
    private static String likePattern(String query) {
        if (query == null || query.isBlank()) {
            return "%";
        }
        String escaped = query.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    @Transactional(readOnly = true)
    public Contact get(Long ownerId, Long id) {
        Contact contact = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Contact %d not found".formatted(id)));
        if (!contact.getOwnerId().equals(ownerId)) {
            throw new NotFoundException("Contact %d not found".formatted(id));
        }
        return contact;
    }

    public Contact create(Long ownerId, ContactRequest req) {
        return repository.save(toEntity(ownerId, req));
    }

    /**
     * Saves the batch in one transaction, skipping any contact whose email the
     * owner already has or that appeared earlier in the same batch (case-insensitive).
     */
    public ContactImportResult importAll(Long ownerId, List<ContactRequest> requests) {
        Set<String> seen = new HashSet<>(repository.findEmailsLowercase(ownerId));
        List<Contact> toSave = new ArrayList<>();
        for (ContactRequest req : requests) {
            if (seen.add(req.email().trim().toLowerCase(Locale.ROOT))) {
                toSave.add(toEntity(ownerId, req));
            }
        }
        repository.saveAll(toSave);
        return new ContactImportResult(toSave.size(), requests.size() - toSave.size());
    }

    private static Contact toEntity(Long ownerId, ContactRequest req) {
        return Contact.builder()
                .ownerId(ownerId)
                .firstName(req.firstName())
                .lastName(req.lastName())
                .email(req.email())
                .phone(req.phone())
                .bio(req.bio())
                .imageUrl(req.imageUrl())
                .street(req.street())
                .city(req.city())
                .state(req.state())
                .country(req.country())
                .zipCode(req.zipCode())
                .githubUrl(req.githubUrl())
                .linkedinUrl(req.linkedinUrl())
                .instagramUrl(req.instagramUrl())
                .build();
    }

    public Contact update(Long ownerId, Long id, ContactRequest req) {
        Contact contact = get(ownerId, id);
        contact.setFirstName(req.firstName());
        contact.setLastName(req.lastName());
        contact.setEmail(req.email());
        contact.setPhone(req.phone());
        contact.setBio(req.bio());
        contact.setImageUrl(req.imageUrl());
        contact.setStreet(req.street());
        contact.setCity(req.city());
        contact.setState(req.state());
        contact.setCountry(req.country());
        contact.setZipCode(req.zipCode());
        contact.setGithubUrl(req.githubUrl());
        contact.setLinkedinUrl(req.linkedinUrl());
        contact.setInstagramUrl(req.instagramUrl());
        return contact;
    }

    public void delete(Long ownerId, Long id) {
        Contact contact = get(ownerId, id);
        repository.delete(contact);
    }
}
