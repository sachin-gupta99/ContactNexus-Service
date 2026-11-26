package com.sachingupta.contactnexus.contact;

import com.sachingupta.contactnexus.common.AfterCommit;
import com.sachingupta.contactnexus.common.NotFoundException;
import com.sachingupta.contactnexus.contact.dto.ContactImportResult;
import com.sachingupta.contactnexus.contact.dto.ContactRequest;
import com.sachingupta.contactnexus.contact.dto.ContactStats;
import com.sachingupta.contactnexus.contact.dto.TagCount;
import com.sachingupta.contactnexus.user.AvatarStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class ContactService {

    private final ContactRepository repository;
    private final TagService tagService;
    private final TagRepository tagRepository;
    private final AvatarStorage storage;

    @Transactional(readOnly = true)
    public Page<Contact> list(Long ownerId, String query, boolean favoritesOnly, String tag, Pageable pageable) {
        String tagFilter = tag == null ? "" : tag.trim().toLowerCase(Locale.ROOT);
        return repository.search(ownerId, likePattern(query), favoritesOnly, tagFilter, pageable);
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
                repository.countByOwnerIdAndCreatedAtAfter(ownerId, Instant.now().minus(7, ChronoUnit.DAYS)),
                tagRepository.countByOwnerId(ownerId));
    }

    @Transactional(readOnly = true)
    public List<TagCount> tags(Long ownerId) {
        return tagRepository.countsForOwner(ownerId).stream()
                .map(row -> new TagCount((String) row[0], (Long) row[1]))
                .toList();
    }

    /** Removes the tag from every contact. */
    public void deleteTag(Long ownerId, String name) {
        if (tagRepository.deleteByOwnerAndLowerName(ownerId, name.trim().toLowerCase(Locale.ROOT)) == 0) {
            throw new NotFoundException("Tag %s not found".formatted(name));
        }
    }

    @Transactional(readOnly = true)
    public List<DuplicateFinder.Group> duplicates(Long ownerId) {
        return DuplicateFinder.find(repository.findAllByOwnerId(ownerId));
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
        Contact contact = toEntity(ownerId, req);
        List<String> names = TagService.normalize(req.tags());
        contact.setTags(tagsFor(names, tagService.resolve(ownerId, names)));
        return repository.save(contact);
    }

    /**
     * Saves the batch in one transaction, skipping any contact whose email the
     * owner already has or that appeared earlier in the same batch (case-insensitive).
     */
    public ContactImportResult importAll(Long ownerId, List<ContactRequest> requests) {
        Set<String> seen = new HashSet<>(repository.findEmailsLowercase(ownerId));
        List<ContactRequest> accepted = new ArrayList<>();
        for (ContactRequest req : requests) {
            if (seen.add(req.email().trim().toLowerCase(Locale.ROOT))) {
                accepted.add(req);
            }
        }

        // One lookup for every tag named anywhere in the batch.
        Set<String> allNames = new LinkedHashSet<>();
        accepted.forEach(r -> allNames.addAll(TagService.normalize(r.tags())));
        Map<String, Tag> resolved = tagService.resolve(ownerId, allNames);

        List<Contact> toSave = new ArrayList<>(accepted.size());
        for (ContactRequest req : accepted) {
            Contact c = toEntity(ownerId, req);
            c.setTags(tagsFor(TagService.normalize(req.tags()), resolved));
            toSave.add(c);
        }
        repository.saveAll(toSave);
        return new ContactImportResult(toSave.size(), requests.size() - toSave.size());
    }

    private static Set<Tag> tagsFor(List<String> names, Map<String, Tag> resolved) {
        Set<Tag> set = new HashSet<>();
        names.forEach(n -> set.add(resolved.get(n.toLowerCase(Locale.ROOT))));
        return set;
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
        // An uploaded photo wins; the client may echo back the presigned URL it
        // was given, which must not be stored as an external link.
        if (contact.getImageKey() == null) {
            contact.setImageUrl(req.imageUrl());
        }
        contact.setStreet(req.street());
        contact.setCity(req.city());
        contact.setState(req.state());
        contact.setCountry(req.country());
        contact.setZipCode(req.zipCode());
        contact.setGithubUrl(req.githubUrl());
        contact.setLinkedinUrl(req.linkedinUrl());
        contact.setInstagramUrl(req.instagramUrl());
        if (req.tags() != null) {
            List<String> names = TagService.normalize(req.tags());
            contact.setTags(tagsFor(names, tagService.resolve(ownerId, names)));
            repository.flush();
            tagService.deleteUnused(ownerId);
        }
        return contact;
    }

    public void delete(Long ownerId, Long id) {
        Contact contact = get(ownerId, id);
        String photoKey = contact.getImageKey();
        repository.delete(contact);
        repository.flush();
        tagService.deleteUnused(ownerId);
        if (photoKey != null) {
            AfterCommit.run(() -> storage.delete(photoKey));
        }
    }

    /** Stores the photo in S3 and replaces any previous one (deleted after commit). */
    public Contact setPhoto(Long ownerId, Long id, MultipartFile file) {
        Contact contact = get(ownerId, id);
        String key = storage.storeContactPhoto(ownerId, file);
        String previous = contact.getImageKey();
        contact.setImageKey(key);
        contact.setImageUrl(null);
        if (previous != null) {
            AfterCommit.run(() -> storage.delete(previous));
        }
        return contact;
    }

    public void removePhoto(Long ownerId, Long id) {
        Contact contact = get(ownerId, id);
        String previous = contact.getImageKey();
        contact.setImageKey(null);
        if (previous != null) {
            AfterCommit.run(() -> storage.delete(previous));
        }
    }
}
