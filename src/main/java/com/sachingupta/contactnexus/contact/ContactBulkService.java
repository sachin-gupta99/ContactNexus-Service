package com.sachingupta.contactnexus.contact;

import com.sachingupta.contactnexus.common.AfterCommit;
import com.sachingupta.contactnexus.contact.dto.BulkRequest;
import com.sachingupta.contactnexus.contact.dto.MergeRequest;
import com.sachingupta.contactnexus.user.AvatarStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Operations on several contacts at once: bulk actions and merging duplicates. */
@Service
@RequiredArgsConstructor
@Transactional
public class ContactBulkService {

    private final ContactRepository contacts;
    private final ContactService contactService;
    private final TagService tagService;
    private final InteractionRepository interactions;
    private final AvatarStorage storage;

    /** Ids that aren't the caller's are ignored; {@code affected} counts the rest. */
    public int apply(Long ownerId, BulkRequest req) {
        List<Contact> mine = contacts.findByOwnerIdAndIdIn(ownerId, new LinkedHashSet<>(req.ids()));
        if (mine.isEmpty()) return 0;

        switch (req.action()) {
            case FAVORITE, UNFAVORITE -> mine.forEach(c -> c.setFavorite(req.action() == BulkRequest.Action.FAVORITE));
            case ADD_TAGS -> {
                List<String> names = requireTags(req);
                Map<String, Tag> resolved = tagService.resolve(ownerId, names);
                for (Contact c : mine) {
                    Set<Tag> next = new HashSet<>(c.getTags());
                    names.forEach(n -> next.add(resolved.get(n.toLowerCase(Locale.ROOT))));
                    if (next.size() > 10) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "%s would have more than 10 tags".formatted(c.getFirstName()));
                    }
                    c.setTags(next);
                }
            }
            case REMOVE_TAGS -> {
                Set<String> lower = new HashSet<>();
                requireTags(req).forEach(n -> lower.add(n.toLowerCase(Locale.ROOT)));
                mine.forEach(c -> c.getTags().removeIf(t -> lower.contains(t.getName().toLowerCase(Locale.ROOT))));
                contacts.flush();
                tagService.deleteUnused(ownerId);
            }
            case DELETE -> {
                List<String> keys = mine.stream().map(Contact::getImageKey).filter(Objects::nonNull).toList();
                // Bulk JPQL delete: contact_tags, interactions and reminder_log rows go
                // with them through ON DELETE CASCADE.
                contacts.deleteByOwnerIdAndIdIn(ownerId, mine.stream().map(Contact::getId).toList());
                tagService.deleteUnused(ownerId);
                if (!keys.isEmpty()) AfterCommit.run(() -> storage.deleteAll(keys));
            }
        }
        return mine.size();
    }

    private static List<String> requireTags(BulkRequest req) {
        List<String> names = TagService.normalize(req.tags());
        if (names.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose at least one tag");
        }
        return names;
    }

    /**
     * Folds {@code mergeIds} into {@code keepId}: the kept contact takes the
     * chosen values, everyone's tags (unless the caller chose them), the timeline
     * of all of them, "favourite" if any was, and the chosen photo. The others
     * are deleted, along with photos nobody keeps.
     */
    public Contact merge(Long ownerId, MergeRequest req) {
        Set<Long> mergeIds = new LinkedHashSet<>(req.mergeIds());
        if (mergeIds.contains(req.keepId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The contact you keep can't also be merged away");
        }
        Contact keep = contactService.get(ownerId, req.keepId());
        List<Contact> others = contacts.findByOwnerIdAndIdIn(ownerId, mergeIds);
        if (others.size() != mergeIds.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Some of those contacts don't exist");
        }
        List<Contact> all = new ArrayList<>(others);
        all.add(0, keep);

        // Photo: the one asked for, else the kept one's, else the first other with a photo.
        Contact photoSource = req.photoFromId() == null
                ? (keep.getImageKey() != null ? keep
                : others.stream().filter(c -> c.getImageKey() != null).findFirst().orElse(keep))
                : all.stream().filter(c -> c.getId().equals(req.photoFromId())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "photoFromId must be one of the merged contacts"));
        String keptKey = photoSource.getImageKey();
        String keptUrl = photoSource.getImageKey() == null ? photoSource.getImageUrl() : null;
        List<String> orphanKeys = all.stream().map(Contact::getImageKey)
                .filter(k -> k != null && !k.equals(keptKey)).toList();

        boolean favorite = all.stream().anyMatch(Contact::isFavorite);
        Instant lastContacted = all.stream().map(Contact::getLastContactedAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        List<String> unionTags = all.stream().flatMap(c -> c.getTags().stream()).map(Tag::getName).toList();

        var values = req.values();
        var withTags = values.tags() != null ? values : new com.sachingupta.contactnexus.contact.dto.ContactRequest(
                values.firstName(), values.lastName(), values.email(), values.phone(), values.bio(), values.imageUrl(),
                values.street(), values.city(), values.state(), values.country(), values.zipCode(),
                values.githubUrl(), values.linkedinUrl(), values.instagramUrl(),
                TagService.normalize(unionTags).stream().limit(10).toList(),
                values.notes(), values.birthdayMonth(), values.birthdayDay(), values.birthdayYear());

        interactions.reassign(ownerId, mergeIds, keep.getId());
        contacts.deleteByOwnerIdAndIdIn(ownerId, mergeIds);
        contacts.flush();

        // update() applies every field (including tags) and cleans up unused tags.
        contactService.update(ownerId, keep.getId(), withTags);
        keep.setFavorite(favorite);
        keep.setLastContactedAt(lastContacted);
        keep.setImageKey(keptKey);
        keep.setImageUrl(keptKey != null ? null : (values.imageUrl() != null && !values.imageUrl().isBlank() ? values.imageUrl() : keptUrl));

        if (!orphanKeys.isEmpty()) AfterCommit.run(() -> storage.deleteAll(orphanKeys));
        return keep;
    }
}
