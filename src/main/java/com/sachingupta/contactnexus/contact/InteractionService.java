package com.sachingupta.contactnexus.contact;

import com.sachingupta.contactnexus.common.NotFoundException;
import com.sachingupta.contactnexus.contact.dto.InteractionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A contact's timeline. {@code Contact.lastContactedAt} always equals the newest
 * entry's time, so lists can show and sort by it without a join.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class InteractionService {

    static final int MAX_LISTED = 200;
    /** Clock skew between a phone and the server shouldn't reject "just now". */
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

    private final ContactService contacts;
    private final InteractionRepository interactions;

    @Transactional(readOnly = true)
    public List<Interaction> list(Long ownerId, Long contactId) {
        contacts.get(ownerId, contactId); // ownership check
        return interactions.findByContactIdOrderByOccurredAtDescIdDesc(contactId, PageRequest.of(0, MAX_LISTED));
    }

    public Interaction add(Long ownerId, Long contactId, InteractionRequest req) {
        Contact contact = contacts.get(ownerId, contactId);
        Instant now = Instant.now();
        Instant when = req.occurredAt() == null ? now : req.occurredAt();
        if (when.isAfter(now.plus(FUTURE_TOLERANCE))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An interaction can't be in the future");
        }
        Interaction saved = interactions.save(Interaction.builder()
                .ownerId(ownerId)
                .contactId(contactId)
                .type(req.type())
                .note(req.note() == null || req.note().isBlank() ? null : req.note().trim())
                .occurredAt(when)
                .build());
        if (contact.getLastContactedAt() == null || when.isAfter(contact.getLastContactedAt())) {
            contact.setLastContactedAt(when);
        }
        return saved;
    }

    public void delete(Long ownerId, Long contactId, Long interactionId) {
        Contact contact = contacts.get(ownerId, contactId);
        Interaction i = interactions.findByIdAndContactIdAndOwnerId(interactionId, contactId, ownerId)
                .orElseThrow(() -> new NotFoundException("Interaction %d not found".formatted(interactionId)));
        interactions.delete(i);
        interactions.flush();
        contact.setLastContactedAt(interactions.latestFor(contactId).orElse(null));
    }
}
