package com.sachingupta.contactnexus.contact;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ContactRepository extends JpaRepository<Contact, Long> {

    /**
     * {@code fuzzy} (lower-cased query, or "" to skip) adds typo-tolerant name
     * matching with pg_trgm's {@code similarity()} against the first name, the
     * last name and the full name: "rvi" finds "Ravi" (~0.29) but not "Ramesh"
     * (~0.1). {@code word_similarity} was too loose for short queries: one shared
     * first letter already scores 0.25 against a 3-letter term.
     *
     * <p>{@code pattern} is a lower-cased LIKE pattern ("%" matches everything), which
     * avoids a nullable String parameter: Postgres can't infer the type of a bare
     * {@code :q is null} check.
     */
    @Query("""
            select c from Contact c
            where c.ownerId = :ownerId
              and (:favoritesOnly = false or c.favorite = true)
              and (:tag = '' or exists (
                    select 1 from Contact c2 join c2.tags t
                    where c2.id = c.id and lower(t.name) = :tag))
              and (lower(c.firstName) like :pattern escape '\\'
                   or lower(c.lastName) like :pattern escape '\\'
                   or lower(c.email) like :pattern escape '\\'
                   or (:fuzzy <> '' and (
                          function('similarity', :fuzzy, lower(c.firstName)) >= 0.25
                       or function('similarity', :fuzzy, lower(coalesce(c.lastName, ''))) >= 0.25
                       or function('similarity', :fuzzy,
                              lower(concat(c.firstName, ' ', coalesce(c.lastName, '')))) >= 0.25)))
            """)
    Page<Contact> search(Long ownerId, String pattern, String fuzzy, boolean favoritesOnly, String tag, Pageable pageable);

    /** Every contact with a birthday, for reminders and the "upcoming" list. */
    @Query("select c from Contact c where c.ownerId = :ownerId and c.birthdayMonth is not null")
    List<Contact> findWithBirthday(Long ownerId);

    /** Contacts whose birthday falls on one of these month/day pairs, across all owners. */
    @Query("""
            select c from Contact c
            where c.birthdayMonth = :month and c.birthdayDay in :days
            """)
    List<Contact> findByBirthday(int month, Collection<Integer> days);

    List<Contact> findByOwnerIdAndIdIn(Long ownerId, Collection<Long> ids);

    @Modifying
    @Query("delete from Contact c where c.ownerId = :ownerId and c.id in :ids")
    int deleteByOwnerIdAndIdIn(Long ownerId, Collection<Long> ids);

    /** For duplicate detection, which has to compare every contact. */
    List<Contact> findAllByOwnerId(Long ownerId);

    @Query("select c.imageKey from Contact c where c.ownerId = :ownerId and c.imageKey is not null")
    List<String> findImageKeysByOwnerId(Long ownerId);

    /** Lower-cased emails the owner already has, for duplicate detection on import. */
    @Query("select lower(c.email) from Contact c where c.ownerId = :ownerId")
    List<String> findEmailsLowercase(Long ownerId);

    long countByOwnerId(Long ownerId);

    long countByOwnerIdAndFavoriteTrue(Long ownerId);

    long countByOwnerIdAndCreatedAtAfter(Long ownerId, Instant since);

    @Modifying
    @Query("delete from Contact c where c.ownerId = :ownerId")
    int deleteByOwnerId(Long ownerId);
}
