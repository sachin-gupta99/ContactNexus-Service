package com.sachingupta.contactnexus.contact;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContactRepository extends JpaRepository<Contact, Long> {

    Page<Contact> findByOwnerId(Long ownerId, Pageable pageable);

    Page<Contact> findByOwnerIdAndFirstNameContainingIgnoreCaseOrOwnerIdAndLastNameContainingIgnoreCaseOrOwnerIdAndEmailContainingIgnoreCase(
            Long ownerId1, String firstName,
            Long ownerId2, String lastName,
            Long ownerId3, String email,
            Pageable pageable);
}
