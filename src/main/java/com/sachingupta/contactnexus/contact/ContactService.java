package com.sachingupta.contactnexus.contact;

import com.sachingupta.contactnexus.common.NotFoundException;
import com.sachingupta.contactnexus.contact.dto.ContactRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ContactService {

    private final ContactRepository repository;

    public Page<Contact> list(Long ownerId, String query, Pageable pageable) {
        if (query == null || query.isBlank()) {
            return repository.findByOwnerId(ownerId, pageable);
        }
        return repository
                .findByOwnerIdAndFirstNameContainingIgnoreCaseOrOwnerIdAndLastNameContainingIgnoreCaseOrOwnerIdAndEmailContainingIgnoreCase(
                        ownerId, query, ownerId, query, ownerId, query, pageable);
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
        Contact contact = Contact.builder()
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
        return repository.save(contact);
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
