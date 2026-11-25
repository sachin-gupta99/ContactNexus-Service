package com.sachingupta.contactnexus.contact;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;

public interface ContactRepository extends JpaRepository<Contact, Long> {

    /**
     * {@code pattern} is a lower-cased LIKE pattern ("%" matches everything), which
     * avoids a nullable String parameter: Postgres can't infer the type of a bare
     * {@code :q is null} check.
     */
    @Query("""
            select c from Contact c
            where c.ownerId = :ownerId
              and (:favoritesOnly = false or c.favorite = true)
              and (lower(c.firstName) like :pattern escape '\\'
                   or lower(c.lastName) like :pattern escape '\\'
                   or lower(c.email) like :pattern escape '\\')
            """)
    Page<Contact> search(Long ownerId, String pattern, boolean favoritesOnly, Pageable pageable);

    long countByOwnerId(Long ownerId);

    long countByOwnerIdAndFavoriteTrue(Long ownerId);

    long countByOwnerIdAndCreatedAtAfter(Long ownerId, Instant since);

    @Modifying
    @Query("delete from Contact c where c.ownerId = :ownerId")
    int deleteByOwnerId(Long ownerId);
}
